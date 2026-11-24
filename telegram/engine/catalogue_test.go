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
func TestDialogCursorAdvancesPastExcludedProtectedPeer(t *testing.T) {
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
	if len(got.Chats) != 1 || got.Next.Peer.ID != "2" || got.Next.ID != 99 || got.Next.Date != 99 {
		t.Fatalf("excluded peer trapped/skipped pagination: %s", b)
	}
}

func TestFirstDialogPageIncludesSavedWithoutDroppingHundredDialogs(t *testing.T) {
    result:=&tg.MessagesDialogsSlice{Count:101}
    for n:=1;n<=100;n++ {
        result.Dialogs=append(result.Dialogs,&tg.Dialog{Peer:&tg.PeerChannel{ChannelID:int64(n)},TopMessage:1000-n})
        result.Chats=append(result.Chats,&tg.Channel{ID:int64(n),AccessHash:1,Title:"fixture"})
        result.Messages=append(result.Messages,&tg.Message{ID:1000-n,Date:1000-n,PeerID:&tg.PeerChannel{ChannelID:int64(n)}})
    }
    c:=&Client{api:tg.NewClient(dialogsInvoker{result:result}),userID:1001}
    raw,err:=c.chats(context.Background(),request{Limit:100,Folder:0});if err!=nil{t.Fatal(err)}
    encoded,_:=json.Marshal(raw);var got struct{Chats []chatRow `json:"chats"`;Next *dialogOffset `json:"next"`};if json.Unmarshal(encoded,&got)!=nil{t.Fatal("invalid page")}
    if len(got.Chats)!=101||got.Chats[0].Title!="Mensajes guardados"||got.Next==nil||got.Next.ID!=900 {t.Fatalf("first page lost dialogs or saved: %s",encoded)}
}
