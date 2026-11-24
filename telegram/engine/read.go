package tgengine

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"math"
	"strconv"
	"strings"
	"time"
)

func (l locator) validate() error {
	if l.Peer.validate() != nil || l.MessageID < 1 || int64(l.MessageID) > math.MaxInt32 || l.DC < 1 || l.DC > 1000 || l.Size < 1 || l.MediaSize < l.Size || len(l.Reference) > 65536 || len(l.Thumb) > 8 {
		return errors.New("BAD_LOCATOR")
	}
	if l.Kind != "photo" && l.Kind != "document" {
		return errors.New("BAD_LOCATOR")
	}
	id, e := strconv.ParseInt(l.ID, 10, 64)
    if e != nil || id == 0 {
		return errors.New("BAD_LOCATOR")
	}
	if _, e = strconv.ParseInt(l.Hash, 10, 64); e != nil {
		return errors.New("BAD_LOCATOR")
	}
	for _, ch := range l.Thumb {
		if !((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
			return errors.New("BAD_LOCATOR")
		}
	}
	if len(l.Revision) != 64 || l.Revision != l.revision() {
		return errors.New("BAD_LOCATOR")
	}
	return nil
}
func (l locator) input() tg.InputFileLocationClass {
	id, _ := strconv.ParseInt(l.ID, 10, 64)
	hash, _ := strconv.ParseInt(l.Hash, 10, 64)
	if l.Kind == "photo" {
		return &tg.InputPhotoFileLocation{ID: id, AccessHash: hash, FileReference: l.Reference, ThumbSize: l.Thumb}
	}
	return &tg.InputDocumentFileLocation{ID: id, AccessHash: hash, FileReference: l.Reference, ThumbSize: l.Thumb}
}

// Read returns one validated range in RAM. It never creates files or uses downloader caches.
func (c *Client) Read(raw string) ([]byte, error) {
	var r readRequest
	if len(raw) > 131072 || json.Unmarshal([]byte(raw), &r) != nil {
		return nil, errors.New("BAD_REQUEST")
	}
	if e := r.validate(); e != nil {
		return nil, e
	}
	if e := r.Locator.validate(); e != nil {
		return nil, e
	}
	if r.Offset+int64(r.Length) > r.Locator.Size {
		return nil, errors.New("BAD_RANGE")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	ctx, cancel := c.requestContext()
	defer cancel()
	if e := c.connect(ctx); e != nil {
		return nil, c.failure(e)
	}
	if e := c.authorized(ctx); e != nil {
		return nil, c.failure(e)
	}
	key := r.Locator.Revision + ":" + r.Locator.Thumb
	l, ok := c.cached(key, r.Offset)
	var e error
	if !ok || r.Offset == 0 {
		l, e = c.refresh(ctx, r.Locator)
		if e != nil {
			return nil, c.failure(e)
		}
		if len(c.verified) >= 64 {
			for k, v := range c.verified {
				clear(v.Locator.Reference)
				delete(c.verified, k)
			}
		}
		c.verified[key] = verifiedMedia{Locator: l, At: time.Now()}
	}
	b, e := c.download(ctx, l, r.Offset, r.Length)
	if e != nil {
		clear(b)
		return nil, c.failure(e)
	}
	return b, nil
}
func (c *Client) refresh(ctx context.Context, old locator) (locator, error) {
	it, e := c.message(ctx, old.Peer, old.MessageID)
	if e != nil {
		return locator{}, e
	}
	if it.Revision != old.Revision || it.Locator.Kind != old.Kind || it.Locator.ID != old.ID {
		return locator{}, errors.New("MEDIA_CHANGED")
	}
	l := it.Locator
	if old.Thumb != l.Thumb {
		if it.ThumbLocator == nil || it.ThumbLocator.Thumb != old.Thumb || it.ThumbLocator.Size != old.Size {
			return locator{}, errors.New("MEDIA_CHANGED")
		}
		l = *it.ThumbLocator
	}
	if l.Size != old.Size {
		return locator{}, errors.New("MEDIA_CHANGED")
	}
	return l, nil
}
func (c *Client) fileAPI(ctx context.Context, dc int) (*tg.Client, error) {
    // Exporting authorization to the already authenticated primary DC is rejected by Telegram.
    if c.storage!=nil&&c.storage.primaryDC()==dc{return c.api,nil}
	if p := c.pools[dc]; p != nil {
		return tg.NewClient(p), nil
	}
	p, e := c.native.DC(ctx, dc, 1)
	if e != nil {
		return nil, e
	}
	c.pools[dc] = p
	return tg.NewClient(p), nil
}
func (c *Client) download(ctx context.Context, l locator, offset int64, length int) ([]byte, error) {
	renewed := false
	migratedDC := 0
	for attempt := 0; attempt < 3; attempt++ {
		api, e := c.fileAPI(ctx, l.DC)
		if e != nil {
			return nil, e
		}
		b, e := readBlocks(ctx, api, l, offset, length)
		if e == nil {
			return b, nil
		}
		rpc, ok := tgerr.As(e)
		if !ok {
			return nil, e
		}
		// Only idempotent file reads can renew one reference and follow one authenticated DC migration.
		if strings.HasPrefix(rpc.Type, "FILE_REFERENCE") && !renewed {
			l, e = c.refresh(ctx, l)
			if e != nil {
				return nil, e
			}
			renewed = true
			if migratedDC != 0 {
				l.DC = migratedDC
			}
		} else if rpc.Type == "FILE_MIGRATE" && migratedDC == 0 && rpc.Argument > 0 && rpc.Argument <= 1000 {
			migratedDC = rpc.Argument
			l.DC = migratedDC
		} else {
			return nil, e
		}
		c.verified[l.Revision+":"+l.Thumb] = verifiedMedia{Locator: l, At: time.Now()}
	}
	return nil, errors.New("MEDIA_UNAVAILABLE")
}
func readBlocks(ctx context.Context, api *tg.Client, l locator, offset int64, length int) ([]byte, error) {
	const unit = 1024
	const boundary = 1048576
	out := make([]byte, 0, length)
	for len(out) < length {
		pos := offset + int64(len(out))
		aligned := pos - pos%unit
		// Telegram precise ranges are 1 KiB aligned and cannot cross a 1 MiB boundary.
		need := int64(length-len(out)) + (pos - aligned)
		physical := min(((need+unit-1)/unit)*unit, 524288, boundary-aligned%boundary)
		v, e := api.UploadGetFile(ctx, &tg.UploadGetFileRequest{Precise: true, CDNSupported: false, Location: l.input(), Offset: aligned, Limit: int(physical)})
		if e != nil {
			clear(out)
			return nil, e
		}
		f, ok := v.(*tg.UploadFile)
		if !ok {
			clear(out)
			return nil, errors.New("UNSUPPORTED_RESPONSE")
		}
		start := int(pos - aligned)
		if len(f.Bytes) <= start || len(f.Bytes) > int(physical) {
			clear(f.Bytes)
			clear(out)
			return nil, errors.New("SHORT_READ")
		}
		n := min(length-len(out), len(f.Bytes)-start)
		out = append(out, f.Bytes[start:start+n]...)
		clear(f.Bytes)
		if n == 0 {
			clear(out)
			return nil, errors.New("SHORT_READ")
		}
	}
	return out, nil
}
