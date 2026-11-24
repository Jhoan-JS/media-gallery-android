package tgengine

import (
	"github.com/gotd/td/tg"
	"testing"
)

func TestDocumentMediaIncludesVideoNotesAndImageDocuments(t *testing.T) {
	p := peer{Kind: "channel", ID: "1", Hash: "2"}
	for _, mime := range []string{"image/png", "video/mp4"} {
		m := &tg.Message{ID: 7, Date: 100, Media: &tg.MessageMediaDocument{Document: &tg.Document{ID: 77, AccessHash: 8, DCID: 4, Size: 123, MimeType: mime, FileReference: []byte{1}, Attributes: []tg.DocumentAttributeClass{&tg.DocumentAttributeVideo{RoundMessage: true, W: 240, H: 240, Duration: 4}}}}}
		it := messageItem(p, m)
		if it == nil || it.Size != 123 || it.Locator.DC != 4 || it.ID != 7 {
			t.Fatalf("missing media: %s %+v", mime, it)
		}
	}
}
func TestProtectedEphemeralAndPaidAreExcluded(t *testing.T) {
	p := peer{Kind: "group", ID: "1"}
	media := &tg.MessageMediaDocument{Document: &tg.Document{ID: 77, AccessHash: 8, DCID: 4, Size: 123, MimeType: "image/png"}}
	for _, m := range []*tg.Message{{ID: 1, Noforwards: true, Media: media}, {ID: 2, TTLPeriod: 20, Media: media}, {ID: 3, Media: &tg.MessageMediaDocument{TTLSeconds: 10, Document: media.Document}}, {ID: 4, Media: &tg.MessageMediaPaidMedia{}}} {
		if messageItem(p, m) != nil {
			t.Fatalf("restricted media accepted: %d", m.ID)
		}
	}
}
func TestSmallestUsefulRemoteThumbnailAndLargestPhoto(t *testing.T) {
	p := peer{Kind: "user", ID: "1", Hash: "2"}
	m := &tg.Message{ID: 5, Media: &tg.MessageMediaPhoto{Photo: &tg.Photo{ID: 4, AccessHash: 5, DCID: 2, Sizes: []tg.PhotoSizeClass{&tg.PhotoStrippedSize{Type: "i", Bytes: []byte{1}}, &tg.PhotoSize{Type: "s", W: 90, H: 90, Size: 10}, &tg.PhotoSize{Type: "m", W: 320, H: 320, Size: 90}, &tg.PhotoSizeProgressive{Type: "y", W: 1280, H: 1280, Sizes: []int{100, 500}}}}}}
	it := messageItem(p, m)
	if it == nil || it.Size != 500 || it.Width != 1280 || it.Locator.Thumb != "y" || it.ThumbLocator == nil || it.ThumbLocator.Thumb != "m" {
		t.Fatalf("bad photo selection: %+v", it)
	}
}
func TestCatalogueScansSparseHistoryWithoutDroppingDocuments(t *testing.T) {
	p := peer{Kind: "group", ID: "1"}
	msgs := []tg.MessageClass{&tg.Message{ID: 100}, &tg.Message{ID: 99, Media: &tg.MessageMediaDocument{Document: &tg.Document{ID: 2, DCID: 1, Size: 6, MimeType: "image/jpeg"}}}, &tg.Message{ID: 98}}
	page := cataloguePage(p, msgs, 1)
	if len(page.Items) != 1 || page.Items[0].ID != 99 || page.Next != 99 || page.Newest != 100 {
		t.Fatalf("lost sparse history: %+v", page)
	}
}
func TestThumbnailNeverExceedsVaultSingleBlock(t *testing.T) {
	v := []photoVariant{{kind: "s", w: 90, h: 90, size: 1000}, {kind: "m", w: 320, h: 320, size: 140000}, {kind: "y", w: 1280, h: 1280, size: 500000}}
	th := thumbVariant(v)
	if th == nil || th.kind != "s" {
		t.Fatalf("unbounded thumb selected: %+v", th)
	}
	if thumbVariant(v[1:]) != nil {
		t.Fatal("overlarge thumbnail must be absent")
	}
}
