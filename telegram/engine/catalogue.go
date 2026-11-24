package tgengine

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"github.com/gotd/td/tg"
)

type chatRow struct {
	Peer  peer   `json:"peer"`
	Title string `json:"title"`
}
type dialogOffset struct {
	Date          int  `json:"date"`
	ID            int  `json:"id"`
	Peer          peer `json:"peer"`
	ExcludePinned bool `json:"exclude_pinned"`
}
type dialogsData interface {
	GetDialogs() []tg.DialogClass
	GetMessages() []tg.MessageClass
	GetChats() []tg.ChatClass
	GetUsers() []tg.UserClass
}
type messagesData interface {
	GetMessages() []tg.MessageClass
	GetChats() []tg.ChatClass
}

func (c *Client) chats(ctx context.Context, r request) (any, error) {
	q := &tg.MessagesGetDialogsRequest{Limit: r.Limit, OffsetPeer: &tg.InputPeerEmpty{}}
	q.SetFolderID(r.Folder)
	if len(r.Offset) > 0 && string(r.Offset) != "null" {
		var o dialogOffset
		if json.Unmarshal(r.Offset, &o) != nil || o.Date < 0 || o.ID < 0 {
			return nil, errors.New("BAD_REQUEST")
		}
		q.OffsetDate = o.Date
		q.OffsetID = o.ID
		q.ExcludePinned = o.ExcludePinned
		if o.ID != 0 {
			if o.Peer.validate() != nil {
				return nil, errors.New("BAD_REQUEST")
			}
			q.OffsetPeer = c.input(o.Peer)
		}
	}
	v, e := c.api.MessagesGetDialogs(ctx, q)
	if e != nil {
		return nil, e
	}
	d, ok := v.(dialogsData)
	if !ok {
		return nil, errors.New("UNSUPPORTED_RESPONSE")
	}
	rows := map[string]chatRow{}
	eligible := map[string]bool{}
	for _, u := range d.GetUsers() {
		if a, ok := u.(*tg.User); ok {
			p := peer{Kind: "user", ID: strconv.FormatInt(a.ID, 10), Hash: strconv.FormatInt(a.AccessHash, 10)}
			name := strings.TrimSpace(a.FirstName + " " + a.LastName)
			if a.Self {
				name = "Mensajes guardados"
			}
			if name == "" {
				name = a.Username
			}
			if name == "" {
				name = "Chat privado"
			}
			rows["user:"+p.ID] = chatRow{p, name}
			eligible["user:"+p.ID] = !a.Deleted && !a.Min && !a.Restricted
		}
	}
	for _, v := range d.GetChats() {
		switch a := v.(type) {
		case *tg.Channel:
			p := peer{Kind: "channel", ID: strconv.FormatInt(a.ID, 10), Hash: strconv.FormatInt(a.AccessHash, 10)}
			rows["channel:"+p.ID] = chatRow{p, a.Title}
			eligible["channel:"+p.ID] = !a.Left && !a.Min && !a.Noforwards && !a.Restricted
		case *tg.Chat:
			p := peer{Kind: "group", ID: strconv.FormatInt(a.ID, 10)}
			rows["group:"+p.ID] = chatRow{p, a.Title}
			eligible["group:"+p.ID] = !a.Left && !a.Deactivated && !a.Noforwards
		}
	}
	out := []chatRow{}
	seen := map[string]bool{}
	var next *dialogOffset
	initial := q.OffsetID == 0 && !q.ExcludePinned
	if initial && r.Folder == 0 {
		p := peer{Kind: "user", ID: strconv.FormatInt(c.userID, 10), Hash: strconv.FormatInt(c.selfHash, 10)}
		out = append(out, chatRow{p, "Mensajes guardados"})
		seen["user:"+p.ID] = true
	}
	for _, v := range d.GetDialogs() {
		dialog, ok := v.(*tg.Dialog)
		if !ok {
			continue
		}
		key := peerKey(dialog.Peer)
		row, exists := rows[key]
		if !dialog.Pinned && dialog.TopMessage > 0 {
			if !exists {
				return nil, errors.New("UNSUPPORTED_RESPONSE")
			}
			date := 0
			for _, m := range d.GetMessages() {
				if m.GetID() == dialog.TopMessage {
					switch msg := m.(type) {
					case *tg.Message:
						if samePeer(row.Peer, msg.PeerID) {
							date = msg.Date
						}
					case *tg.MessageService:
						if samePeer(row.Peer, msg.PeerID) {
							date = msg.Date
						}
					}
					if date > 0 {
						break
					}
				}
			}
			next = &dialogOffset{Date: date, ID: dialog.TopMessage, Peer: row.Peer, ExcludePinned: true}
		}
		if exists && eligible[key] && !seen[key] {
			out = append(out, row)
			seen[key] = true
		}
	}
	// A full slice may contain only pinned dialogs. Resume the normal stream from its start.
	if next == nil && len(d.GetDialogs()) > 0 && initial {
		next = &dialogOffset{ExcludePinned: true}
	}
	if _, full := v.(*tg.MessagesDialogs); full {
		next = nil
	}
	if len(d.GetDialogs()) == 0 {
		next = nil
	}
	return map[string]any{"chats": out, "next": next}, nil
}
func peerKey(v tg.PeerClass) string {
	switch p := v.(type) {
	case *tg.PeerUser:
		return "user:" + strconv.FormatInt(p.UserID, 10)
	case *tg.PeerChat:
		return "group:" + strconv.FormatInt(p.ChatID, 10)
	case *tg.PeerChannel:
		return "channel:" + strconv.FormatInt(p.ChannelID, 10)
	}
	return ""
}
func (c *Client) page(ctx context.Context, r request) (any, error) {
	if r.Cursor == -1 {
		return pageResult{Items: []item{}, Next: -1}, nil
	}
	v, e := c.api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{Peer: c.input(r.Peer), OffsetID: r.Cursor, Limit: 50})
	if e != nil {
		return nil, e
	}
	m, ok := v.(messagesData)
	if !ok {
		return nil, errors.New("UNSUPPORTED_RESPONSE")
	}
	if protected(r.Peer, m.GetChats()) {
		return nil, errors.New("PROTECTED_CONTENT")
	}
	return cataloguePage(r.Peer, m.GetMessages(), r.Limit), nil
}
func (c *Client) message(ctx context.Context, p peer, id int) (*item, error) {
	var v tg.MessagesMessagesClass
	var e error
	if p.Kind == "channel" {
		v, e = c.api.ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{Channel: channelInput(p), ID: []tg.InputMessageClass{&tg.InputMessageID{ID: id}}})
	} else {
		v, e = c.api.MessagesGetMessages(ctx, []tg.InputMessageClass{&tg.InputMessageID{ID: id}})
	}
	if e != nil {
		return nil, e
	}
	m, ok := v.(messagesData)
	if !ok {
		return nil, errors.New("UNSUPPORTED_RESPONSE")
	}
	if protected(p, m.GetChats()) {
		return nil, errors.New("PROTECTED_CONTENT")
	}
	for _, v := range m.GetMessages() {
		if msg, ok := v.(*tg.Message); ok && msg.ID == id && samePeer(p, msg.PeerID) {
			if it := messageItem(p, msg); it != nil {
				return it, nil
			}
		}
	}
	return nil, errors.New("MEDIA_UNAVAILABLE")
}
