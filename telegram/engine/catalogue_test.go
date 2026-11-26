package tgengine

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/tg"
	"testing"
)

type dialogsInvoker struct{ result tg.MessagesDialogsClass }

func (d dialogsInvoker) Invoke(_ context.Context, in bin.Encoder, out bin.Decoder) error {
	if _, ok := in.(*tg.MessagesGetDialogsRequest); !ok {
		return errors.New("unexpected request")
	}
	out.(*tg.MessagesDialogsBox).Dialogs = d.result
	return nil
}
func TestDialogCursorAdvancesPastUnselectableProtectedPeer(t *testing.T) {
	result := &tg.MessagesDialogsSlice{Count: 3, Dialogs: []tg.DialogClass{&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 1}, TopMessage: 100}, &tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 2}, TopMessage: 99}}, Chats: []tg.ChatClass{&tg.Channel{ID: 1, AccessHash: 11, Title: "visible"}, &tg.Channel{ID: 2, AccessHash: 22, Title: "protected", Noforwards: true}}, Messages: []tg.MessageClass{&tg.Message{ID: 100, Date: 100, PeerID: &tg.PeerChannel{ChannelID: 1}}, &tg.Message{ID: 99, Date: 99, PeerID: &tg.PeerChannel{ChannelID: 2}}}}
	c := &Client{api: tg.NewClient(dialogsInvoker{result: result}), userID: 17}
	out, e := c.chats(context.Background(), request{Limit: 2, Folder: 1})
	if e != nil {
		t.Fatal(e)
	}
	b, _ := json.Marshal(out)
	var got struct {
		Chats []chatRow    `json:"chats"`
		Next  dialogOffset `json:"next"`
	}
	_ = json.Unmarshal(b, &got)
	var selection struct {
		Chats []struct{ Unavailable bool } `json:"chats"`
	}
	_ = json.Unmarshal(b, &selection)
	if len(got.Chats) != 2 || selection.Chats[0].Unavailable || !selection.Chats[1].Unavailable || got.Next.Peer.ID != "2" || got.Next.ID != 99 || got.Next.Date != 99 {
		t.Fatalf("protected peer was hidden, selectable, or blocked pagination: %s", b)
	}
}

func TestFirstDialogPageIncludesSavedWithoutDroppingHundredDialogs(t *testing.T) {
	result := &tg.MessagesDialogsSlice{Count: 101}
	for n := 1; n <= 100; n++ {
		result.Dialogs = append(result.Dialogs, &tg.Dialog{Peer: &tg.PeerChannel{ChannelID: int64(n)}, TopMessage: 1000 - n})
		result.Chats = append(result.Chats, &tg.Channel{ID: int64(n), AccessHash: 1, Title: "fixture"})
		result.Messages = append(result.Messages, &tg.Message{ID: 1000 - n, Date: 1000 - n, PeerID: &tg.PeerChannel{ChannelID: int64(n)}})
	}
	c := &Client{api: tg.NewClient(dialogsInvoker{result: result}), userID: 1001}
	raw, err := c.chats(context.Background(), request{Limit: 100, Folder: 0})
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(raw)
	var got struct {
		Chats []chatRow     `json:"chats"`
		Next  *dialogOffset `json:"next"`
	}
	if json.Unmarshal(encoded, &got) != nil {
		t.Fatal("invalid page")
	}
	if len(got.Chats) != 101 || got.Chats[0].Title != "Mensajes guardados" || got.Next == nil || got.Next.ID != 900 {
		t.Fatalf("first page lost dialogs or saved: %s", encoded)
	}
}

func TestInaccessibleDialogsDoNotHidePrivateGroupsAndChannels(t *testing.T) {
	result := &tg.MessagesDialogs{
		Dialogs: []tg.DialogClass{
			&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 4}, TopMessage: 105},
			&tg.Dialog{Peer: &tg.PeerUser{UserID: 5}, TopMessage: 104},
			&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 1}, TopMessage: 103},
			&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 2}, TopMessage: 102},
			&tg.Dialog{Peer: &tg.PeerChat{ChatID: 3}, TopMessage: 101},
		},
		Chats: []tg.ChatClass{
			&tg.ChannelForbidden{ID: 4, AccessHash: 44, Title: "Inaccessible channel"},
			&tg.Channel{ID: 1, AccessHash: 11, Title: "Private channel"},
			&tg.Channel{ID: 2, AccessHash: 22, Title: "Private supergroup", Megagroup: true},
			&tg.Chat{ID: 3, Title: "Private group"},
		},
		Users: []tg.UserClass{&tg.UserEmpty{ID: 5}},
	}
	c := &Client{api: tg.NewClient(dialogsInvoker{result: result}), userID: 17}
	raw, err := c.chats(context.Background(), request{Limit: 100, Folder: 1})
	if err != nil {
		t.Fatal(err)
	}
	got := raw.(map[string]any)["chats"].([]chatRow)
	if len(got) != 3 || got[0].Title != "Private channel" || got[1].Title != "Private supergroup" || got[2].Title != "Private group" {
		t.Fatalf("archived page lost private chats: %+v", got)
	}
}

func TestDialogCursorCanUseAnInaccessibleChannelWithoutListingIt(t *testing.T) {
	result := &tg.MessagesDialogsSlice{
		Count: 3,
		Dialogs: []tg.DialogClass{
			&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 1}, TopMessage: 100},
			&tg.Dialog{Peer: &tg.PeerChannel{ChannelID: 2}, TopMessage: 99},
		},
		Chats: []tg.ChatClass{
			&tg.Channel{ID: 1, AccessHash: 11, Title: "Private channel"},
			&tg.ChannelForbidden{ID: 2, AccessHash: 22, Title: "Inaccessible channel"},
		},
		Messages: []tg.MessageClass{
			&tg.Message{ID: 100, Date: 1700000100, PeerID: &tg.PeerChannel{ChannelID: 1}},
			&tg.MessageEmpty{ID: 99, PeerID: &tg.PeerChannel{ChannelID: 2}},
		},
	}
	c := &Client{api: tg.NewClient(dialogsInvoker{result: result}), userID: 17}
	raw, err := c.chats(context.Background(), request{Limit: 2, Folder: 1})
	if err != nil {
		t.Fatal(err)
	}
	got := raw.(map[string]any)
	next := got["next"].(*dialogOffset)
	if rows := got["chats"].([]chatRow); len(rows) != 1 || next == nil || next.ID != 99 || next.Peer.ID != "2" || next.Peer.Hash != "22" {
		t.Fatalf("page or usable cursor lost: %+v", got)
	}
}

func TestRepeatedDialogCursorKeepsRowsAndEndsIncompletePage(t *testing.T) {
	result := &tg.MessagesDialogsSlice{
		Count: 4,
		Dialogs: []tg.DialogClass{
			&tg.Dialog{Peer: &tg.PeerChat{ChatID: 1}, TopMessage: 91},
			&tg.Dialog{Peer: &tg.PeerUser{UserID: 2}, TopMessage: 90},
		},
		Chats: []tg.ChatClass{&tg.Chat{ID: 1, Title: "Private group"}},
		Users: []tg.UserClass{&tg.UserEmpty{ID: 2}},
		Messages: []tg.MessageClass{
			&tg.Message{ID: 91, Date: 1700000091, PeerID: &tg.PeerChat{ChatID: 1}},
		},
	}
	c := &Client{api: tg.NewClient(dialogsInvoker{result: result}), userID: 17}
	oldOffset := json.RawMessage(`{"peer":{"kind":"group","id":"1"},"id":91,"date":1700000091,"exclude_pinned":true}`)
	raw, err := c.chats(context.Background(), request{Limit: 2, Folder: 1, Offset: oldOffset})
	if err != nil {
		t.Fatal(err)
	}
	got := raw.(map[string]any)
	if len(got["chats"].([]chatRow)) != 1 || got["next"].(*dialogOffset) != nil || got["incomplete"] != true {
		t.Fatalf("repeated cursor hid a valid row or failed to signal incomplete listing: %+v", got)
	}
}
