package tgengine

import (
	"bytes"
	"context"
	"errors"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"testing"
)

type fileInvoker struct {
	data    []byte
	offsets []int64
	limits  []int
}

func (f *fileInvoker) Invoke(_ context.Context, in bin.Encoder, out bin.Decoder) error {
	q, ok := in.(*tg.UploadGetFileRequest)
	if !ok {
		return errors.New("unexpected RPC")
	}
	f.offsets = append(f.offsets, q.Offset)
	f.limits = append(f.limits, q.Limit)
	if q.Offset%1024 != 0 || q.Limit%1024 != 0 || q.Limit < 1024 || q.Limit > 524288 || q.Offset/1048576 != (q.Offset+int64(q.Limit)-1)/1048576 || !q.Precise || q.CDNSupported {
		return errors.New("unbounded or misaligned request")
	}
	end := min(int64(len(f.data)), q.Offset+int64(q.Limit))
	var b []byte
	if q.Offset < end {
		b = append([]byte(nil), f.data[q.Offset:end]...)
	}
	out.(*tg.UploadFileBox).File = &tg.UploadFile{Bytes: b}
	return nil
}
func TestReadBlocksReturnsOnlyRequestedUnalignedRange(t *testing.T) {
	f := &fileInvoker{data: bytes.Repeat([]byte{1, 2, 3, 4, 5, 6, 7}, 100000)}
	l := locator{Kind: "document", ID: "1", Hash: "2", Size: int64(len(f.data))}
	got, e := readBlocks(context.Background(), tg.NewClient(f), l, 131071, 524288)
	if e != nil {
		t.Fatal(e)
	}
	if !bytes.Equal(got, f.data[131071:131071+524288]) {
		t.Fatal("range bytes differ")
	}
	if len(f.offsets) != 2 {
		t.Fatalf("too many round trips for one unaligned range: %v", f.offsets)
	}
}
func TestReadBlocksSplitsOnlyAtOneMiBBoundary(t *testing.T) {
	f := &fileInvoker{data: bytes.Repeat([]byte{1, 2, 3, 4, 5, 6, 7}, 300000)}
	got, e := readBlocks(context.Background(), tg.NewClient(f), locator{Kind: "document", ID: "1", Hash: "2", Size: int64(len(f.data))}, 1048575, 524288)
	if e != nil || !bytes.Equal(got, f.data[1048575:1048575+524288]) {
		t.Fatalf("boundary range differs: %v", e)
	}
	if len(f.offsets) != 2 {
		t.Fatalf("too many boundary requests: %v", f.offsets)
	}
}
func TestReadBlocksRejectsTruncatedOriginal(t *testing.T) {
	f := &fileInvoker{data: []byte{1, 2, 3}}
	_, e := readBlocks(context.Background(), tg.NewClient(f), locator{Kind: "document", ID: "1", Hash: "2", Size: 100}, 0, 100)
	if e == nil || e.Error() != "SHORT_READ" {
		t.Fatalf("truncated original accepted: %v", e)
	}
}
func TestThumbnailRevisionMatchesOriginal(t *testing.T) {
	a := locator{Peer: peer{Kind: "group", ID: "1"}, MessageID: 1, Kind: "photo", ID: "2", MediaSize: 1000, Size: 1000}
	a.Revision = a.revision()
	th := a
	th.Size = 100
	th.Thumb = "m"
	if th.revision() != a.Revision {
		t.Fatal("thumbnail lost original identity")
	}
}

type retryFileInvoker struct {
	fileInvoker
	fail       error
	references [][]byte
}

func (f *retryFileInvoker) Invoke(ctx context.Context, in bin.Encoder, out bin.Decoder) error {
	q := in.(*tg.UploadGetFileRequest)
	if l, ok := q.Location.(*tg.InputDocumentFileLocation); ok {
		f.references = append(f.references, append([]byte(nil), l.FileReference...))
	}
	if f.fail != nil {
		e := f.fail
		f.fail = nil
		return e
	}
	return f.fileInvoker.Invoke(ctx, in, out)
}
func (f *retryFileInvoker) Close() error { return nil }

type messageInvoker struct{ message *tg.Message }

func (m messageInvoker) Invoke(_ context.Context, in bin.Encoder, out bin.Decoder) error {
	if _, ok := in.(*tg.MessagesGetMessagesRequest); !ok {
		return errors.New("unexpected message RPC")
	}
	out.(*tg.MessagesMessagesBox).Messages = &tg.MessagesMessages{Messages: []tg.MessageClass{m.message}}
	return nil
}
func TestDownloadFollowsAuthorizedFileDCMigrationOnce(t *testing.T) {
	first := &retryFileInvoker{fail: tgerr.New(303, "FILE_MIGRATE_2")}
	second := &retryFileInvoker{fileInvoker: fileInvoker{data: []byte{1, 2, 3}}}
	c := &Client{pools: map[int]telegram.CloseInvoker{1: first, 2: second}, verified: map[string]verifiedMedia{}}
	got, e := c.download(context.Background(), locator{Kind: "document", ID: "2", Hash: "3", DC: 1, Size: 3}, 0, 3)
	if e != nil || !bytes.Equal(got, []byte{1, 2, 3}) || len(first.references) != 1 || len(second.references) != 1 {
		t.Fatalf("DC migration failed: %v %v", got, e)
	}
}
func TestPrimaryFileDCReusesAuthenticatedMainConnection(t *testing.T){
    f:=&fileInvoker{data:[]byte{1,2,3}}
    c:=&Client{api:tg.NewClient(f),storage:&sessionStore{pending:[]byte(`{"Version":1,"Data":{"DC":2}}`)},pools:map[int]telegram.CloseInvoker{}}
    got,err:=c.download(context.Background(),locator{Kind:"document",ID:"2",Hash:"3",DC:2,Size:3},0,3)
    if err!=nil||!bytes.Equal(got,[]byte{1,2,3})||len(f.offsets)!=1{t.Fatalf("main DC should not export authorization to itself: %v %v",got,err)}
}
func TestDownloadRefreshesOnlyOriginalMessageAndKeepsRevision(t *testing.T) {
	p := peer{Kind: "group", ID: "1"}
	doc := &tg.Document{ID: 2, AccessHash: 3, DCID: 1, Size: 3, MimeType: "image/png", FileReference: []byte{8, 9}}
	msg := &tg.Message{ID: 7, PeerID: &tg.PeerChat{ChatID: 1}, Media: &tg.MessageMediaDocument{Document: doc}}
	old := messageItem(p, msg).Locator
	old.Reference = []byte{1}
	f := &retryFileInvoker{fileInvoker: fileInvoker{data: []byte{1, 2, 3}}, fail: tgerr.New(400, "FILE_REFERENCE_EXPIRED")}
	c := &Client{api: tg.NewClient(messageInvoker{message: msg}), pools: map[int]telegram.CloseInvoker{1: f}, verified: map[string]verifiedMedia{}}
	got, e := c.download(context.Background(), old, 0, 3)
	if e != nil || !bytes.Equal(got, []byte{1, 2, 3}) || len(f.references) != 2 || !bytes.Equal(f.references[1], doc.FileReference) {
		t.Fatalf("reference renewal failed: %v %v %v", got, e, f.references)
	}
	doc.ID = 4
	if _, e = c.refresh(context.Background(), old); e == nil || e.Error() != "MEDIA_CHANGED" {
		t.Fatal("renewal accepted replacement media")
	}
}
