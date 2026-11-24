package tgengine

import (
	"fmt"
	"github.com/gotd/td/tg"
	"sort"
	"strings"
	"unicode"
)

type item struct {
	ID           int      `json:"id"`
	Name         string   `json:"name"`
	MIME         string   `json:"mime"`
	Size         int64    `json:"size"`
	Date         int      `json:"date"`
	Width        int      `json:"width"`
	Height       int      `json:"height"`
	Duration     float64  `json:"duration"`
	Revision     string   `json:"revision"`
	ContentKey   string   `json:"content_key"`
	Locator      locator  `json:"locator"`
	ThumbLocator *locator `json:"thumb_locator"`
}
type pageResult struct {
	Items  []item `json:"items"`
	Next   int    `json:"next"`
	Newest int    `json:"newest"`
}
type photoVariant struct {
	kind string
	w, h int
	size int64
}

func variants(sizes []tg.PhotoSizeClass) []photoVariant {
	out := []photoVariant{}
	for _, v := range sizes {
		switch s := v.(type) {
		case *tg.PhotoSize:
			if s.Size > 0 {
				out = append(out, photoVariant{s.Type, s.W, s.H, int64(s.Size)})
			}
		case *tg.PhotoSizeProgressive:
			if len(s.Sizes) > 0 {
				out = append(out, photoVariant{s.Type, s.W, s.H, int64(s.Sizes[len(s.Sizes)-1])})
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return int64(out[i].w)*int64(out[i].h) < int64(out[j].w)*int64(out[j].h) })
	return out
}
func thumbVariant(v []photoVariant) *photoVariant {
	var fallback *photoVariant
	for i := range v {
		if v[i].size < 1 || v[i].size > 131072 {
			continue
		}
		fallback = &v[i]
		if max(v[i].w, v[i].h) >= 160 {
			return &v[i]
		}
	}
	return fallback
}
func messageItem(p peer, m *tg.Message) *item {
	if m == nil || m.ID <= 0 || m.Noforwards || m.TTLPeriod > 0 || len(m.RestrictionReason) > 0 {
		return nil
	}
	l := locator{Peer: p, MessageID: m.ID}
	it := item{ID: m.ID, Date: m.Date}
	var thumbs []photoVariant
	switch media := m.Media.(type) {
	case *tg.MessageMediaPhoto:
		if media.TTLSeconds > 0 {
			return nil
		}
		photo, ok := media.Photo.(*tg.Photo)
		if !ok {
			return nil
		}
		sizes := variants(photo.Sizes)
		if len(sizes) == 0 {
			return nil
		}
		largest := sizes[len(sizes)-1]
		l.Kind = "photo"
		l.ID = fmt.Sprint(photo.ID)
		l.Hash = fmt.Sprint(photo.AccessHash)
		l.Reference = append([]byte(nil), photo.FileReference...)
		l.DC = photo.DCID
		l.Size = largest.size
		l.Thumb = largest.kind
		it.MIME = "image/jpeg"
		it.Name = fmt.Sprintf("photo_%d.jpg", m.ID)
		it.Width = largest.w
		it.Height = largest.h
		thumbs = sizes
	case *tg.MessageMediaDocument:
		if media.TTLSeconds > 0 {
			return nil
		}
		d, ok := media.Document.(*tg.Document)
		if !ok || d.Size <= 0 {
			return nil
		}
		mime := strings.ToLower(strings.TrimSpace(strings.SplitN(d.MimeType, ";", 2)[0]))
		if !strings.HasPrefix(mime, "image/") && !strings.HasPrefix(mime, "video/") {
			return nil
		}
		l.Kind = "document"
		l.ID = fmt.Sprint(d.ID)
		l.Hash = fmt.Sprint(d.AccessHash)
		l.Reference = append([]byte(nil), d.FileReference...)
		l.DC = d.DCID
		l.Size = d.Size
		it.MIME = mime
		it.Name = fmt.Sprintf("media_%d%s", m.ID, mediaExtension(mime))
		thumbs = variants(d.Thumbs)
		for _, a := range d.Attributes {
			switch v := a.(type) {
			case *tg.DocumentAttributeImageSize:
				it.Width = v.W
				it.Height = v.H
			case *tg.DocumentAttributeVideo:
				it.Width = v.W
				it.Height = v.H
				it.Duration = v.Duration
			case *tg.DocumentAttributeFilename:
				if n := safeName(v.FileName); n != "" {
					it.Name = n
				}
			}
		}
	default:
		return nil
	}
	if l.DC < 1 || l.Size < 1 {
		return nil
	}
	l.MediaSize = l.Size
	l.Revision = l.revision()
	it.Locator = l
	it.Revision = l.Revision
	it.ContentKey = l.Revision
	it.Size = l.Size
	if t := thumbVariant(thumbs); t != nil {
		th := l
		th.Thumb = t.kind
		th.Size = t.size
		it.ThumbLocator = &th
	}
	return &it
}
func safeName(s string) string {
	s = strings.ReplaceAll(s, "\\", "/")
	parts := strings.Split(s, "/")
	s = parts[len(parts)-1]
	r := []rune(strings.Map(func(c rune) rune {
		if unicode.IsControl(c) {
			return -1
		}
		return c
	}, s))
	if len(r) > 180 {
		r = r[:180]
	}
	s = strings.TrimSpace(string(r))
	if s == "." || s == ".." {
		return ""
	}
	return s
}
func boundedText(s string, limit int) string {
	r := []rune(strings.Map(func(c rune) rune {
		if unicode.IsControl(c) {
			return -1
		}
		return c
	}, s))
	if len(r) > limit {
		r = r[:limit]
	}
	return string(r)
}
func mediaExtension(mime string) string {
	switch mime {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "video/mp4":
		return ".mp4"
	case "video/webm":
		return ".webm"
	case "video/quicktime":
		return ".mov"
	}
	return ".bin"
}
func cataloguePage(p peer, m []tg.MessageClass, limit int) pageResult {
	out := pageResult{Items: []item{}, Next: -1}
	sort.SliceStable(m, func(i, j int) bool { return m[i].GetID() > m[j].GetID() })
	for _, v := range m {
		id := v.GetID()
		if id <= 0 {
			continue
		}
		if out.Newest == 0 {
			out.Newest = id
		}
		out.Next = id
		if msg, ok := v.(*tg.Message); ok {
			if it := messageItem(p, msg); it != nil {
				out.Items = append(out.Items, *it)
				if len(out.Items) == limit {
					break
				}
			}
		}
	}
	return out
}
