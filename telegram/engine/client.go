// Package tgengine binds a bounded, diskless Telegram MTProto client to Android.
package tgengine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
)

// Client owns one session. Close cancels outstanding RPCs and discards RAM state.
type Client struct {
	mu          sync.Mutex
	start       sync.Once
	close       sync.Once
	ctx         context.Context
	cancel      context.CancelFunc
	ready       chan struct{}
	done        chan struct{}
	native      *telegram.Client
	api         *tg.Client
	storage     *sessionStore
	userID      int64
	identity    int64
	displayName string
	username    string
	selfHash    int64
	state       string
	phone       string
	codeHash    string
	pools       map[int]telegram.CloseInvoker
	sponsored   map[string][]byte
	verified    map[string]verifiedMedia
}
type verifiedMedia struct {
	Locator locator
	At      time.Time
}

func (c *Client) acceptSelf(u *tg.User) (any, error) {
	if u == nil || u.ID <= 0 {
		return nil, errors.New("AUTH_REQUIRED")
	}
	if c.identity != 0 && c.identity != u.ID {
		return nil, errors.New("ACCOUNT_CHANGED")
	}
	if e := c.storage.authorize(); e != nil {
		return nil, e
	}
	c.userID = u.ID
	c.identity = u.ID
	c.selfHash = u.AccessHash
	c.displayName = boundedText(strings.TrimSpace(u.FirstName+" "+u.LastName), 180)
	c.username = boundedText(u.Username, 64)
	c.state = "authorized"
	c.phone = ""
	c.codeHash = ""
	return c.statusJSON(), nil
}
func (c *Client) cached(key string, offset int64) (locator, bool) {
	v, ok := c.verified[key]
	return v.Locator, ok && offset != 0 && time.Since(v.At) >= 0 && time.Since(v.At) < 15*time.Second
}

// NewClient performs no network request. Credentials are supplied locally at runtime.
func NewClient(apiID int64, apiHash string, store Store) (*Client, error) {
	if apiID < 1 || apiID > math.MaxInt32 || len(apiHash) != 32 || store == nil {
		return nil, errors.New("BAD_REQUEST")
	}
	for _, ch := range apiHash {
		if !(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'f') && !(ch >= 'A' && ch <= 'F') {
			return nil, errors.New("BAD_REQUEST")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{ctx: ctx, cancel: cancel, ready: make(chan struct{}), done: make(chan struct{}), state: "phone", storage: &sessionStore{store: store}, pools: map[int]telegram.CloseInvoker{}, sponsored: map[string][]byte{}, verified: map[string]verifiedMedia{}}
	c.native = telegram.NewClient(int(apiID), apiHash, telegram.Options{SessionStorage: c.storage, NoUpdates: true, DialTimeout: 15 * time.Second, ExchangeTimeout: 20 * time.Second, Device: telegram.DeviceConfig{DeviceModel: "Tato Galería", SystemVersion: "Android", AppVersion: "Conector TG 1.0", SystemLangCode: "es", LangCode: "es"}})
	c.api = c.native.API()
	return c, nil
}
func (c *Client) connect(ctx context.Context) error {
	c.start.Do(func() {
		go func() {
			defer close(c.done)
			_ = c.native.Run(c.ctx, func(ctx context.Context) error { close(c.ready); <-ctx.Done(); return ctx.Err() })
		}()
	})
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-c.ctx.Done():
		return errors.New("CLIENT_CLOSED")
	case <-c.done:
		return errors.New("NETWORK_ERROR")
	case <-c.ready:
		return nil
	}
}
func (c *Client) requestContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(c.ctx, 45*time.Second)
}
func (c *Client) status(ctx context.Context) (any, error) {
	if c.userID != 0 {
		if c.storage != nil {
			if e := c.storage.authorize(); e != nil {
				return nil, e
			}
		}
		return c.statusJSON(), nil
	}
	s, e := c.native.Auth().Status(ctx)
	if e != nil {
		return nil, e
	}
	if !s.Authorized || s.User == nil {
		c.userID = 0
		return map[string]any{"authorized": false, "account": "", "user_id": "", "state": c.state}, nil
	}
	return c.acceptSelf(s.User)
}
func (c *Client) statusJSON() any {
	return map[string]any{"authorized": true, "account": accountID(c.userID), "user_id": strconv.FormatInt(c.userID, 10), "state": "authorized", "display_name": c.displayName, "username": c.username}
}
func (c *Client) failure(e error) error {
	if auth.IsUnauthorized(e) {
		c.userID = 0
		c.state = "phone"
		c.phone = ""
		c.codeHash = ""
		c.displayName = ""
		c.username = ""
		for k, l := range c.verified {
			clear(l.Locator.Reference)
			delete(c.verified, k)
		}
		for k, v := range c.sponsored {
			clear(v)
			delete(c.sponsored, k)
		}
		for k, p := range c.pools {
			_ = p.Close()
			delete(c.pools, k)
		}
		c.storage.mu.Lock()
		c.storage.authorized = false
		c.storage.mu.Unlock()
	}
	return safeError(e)
}
func (c *Client) authorized(ctx context.Context) error {
	if c.userID != 0 {
		return nil
	}
	if _, e := c.status(ctx); e != nil {
		return e
	}
	if c.userID == 0 {
		return errors.New("AUTH_REQUIRED")
	}
	return nil
}

// Request returns a direct JSON object; errors contain only stable, redacted codes.
func (c *Client) Request(raw string) (string, error) {
	r, e := parseRequest(raw)
	if e != nil {
		return "", e
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	ctx, cancel := c.requestContext()
	defer cancel()
	if e = c.connect(ctx); e != nil {
		return "", c.failure(e)
	}
	var out any
	switch r.Op {
	case "status":
		out, e = c.status(ctx)
	case "login_phone":
		out, e = c.status(ctx)
		if e != nil || c.userID != 0 {
			break
		}
		var code tg.AuthSentCodeClass
		code, e = c.native.Auth().SendCode(ctx, r.Phone, auth.SendCodeOptions{})
		if e != nil {
			break
		}
		switch v := code.(type) {
		case *tg.AuthSentCode:
			c.phone = r.Phone
			c.codeHash = v.PhoneCodeHash
			c.state = "code"
			out = map[string]any{"authorized": false, "account": "", "user_id": "", "state": "code"}
		case *tg.AuthSentCodeSuccess:
			out, e = c.status(ctx)
		default:
			e = errors.New("UNSUPPORTED_RESPONSE")
		}
	case "login_code":
		if c.phone == "" || c.codeHash == "" {
			e = errors.New("LOGIN_REQUIRED")
			break
		}
		_, e = c.native.Auth().SignIn(ctx, c.phone, r.Code, c.codeHash)
		if errors.Is(e, auth.ErrPasswordAuthNeeded) {
			c.state = "password"
			e = nil
			out = map[string]any{"authorized": false, "account": "", "user_id": "", "state": "password"}
		} else if e == nil {
			out, e = c.status(ctx)
		}
	default:
		if e = c.authorized(ctx); e != nil {
			break
		}
		switch r.Op {
		case "chats":
			out, e = c.chats(ctx, r)
		case "page":
			out, e = c.page(ctx, r)
		case "opened":
			e = c.opened(ctx, r)
			out = map[string]bool{"ok": e == nil}
		case "sponsored":
			out, e = c.getSponsored(ctx, r.Peer)
		case "sponsored_view", "sponsored_click":
			e = c.sponsoredAction(ctx, r)
			out = map[string]bool{"ok": e == nil}
		}
	}
	if e != nil {
		return "", c.failure(e)
	}
	b, e := json.Marshal(out)
	if e != nil {
		return "", errors.New("UNSUPPORTED_RESPONSE")
	}
	return string(b), nil
}

// Password uses SRP directly from bytes and erases the supplied Go byte slice.
func (c *Client) Password(password []byte) (string, error) {
	defer clear(password)
	if len(password) < 1 || len(password) > 1024 {
		return "", errors.New("BAD_REQUEST")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	ctx, cancel := c.requestContext()
	defer cancel()
	if e := c.connect(ctx); e != nil {
		return "", c.failure(e)
	}
	if c.state != "password" {
		return "", errors.New("LOGIN_REQUIRED")
	}
	if _, e := c.native.Auth().PasswordWith(ctx, auth.PasswordHashFor(password)); e != nil {
		return "", c.failure(e)
	}
	s, e := c.status(ctx)
	if e != nil {
		return "", c.failure(e)
	}
	b, e := json.Marshal(s)
	return string(b), e
}

// Close is idempotent and cancels current and queued work without sending logout.
func (c *Client) Close() {
	c.close.Do(func() {
		c.cancel()
		c.mu.Lock()
		defer c.mu.Unlock()
		for _, p := range c.pools {
			_ = p.Close()
		}
		c.pools = nil
		for k, v := range c.sponsored {
			clear(v)
			delete(c.sponsored, k)
		}
		for k, l := range c.verified {
			clear(l.Locator.Reference)
			delete(c.verified, k)
		}
		c.phone = ""
		c.codeHash = ""
		c.userID = 0
		c.storage.wipe()
	})
}
func (c *Client) input(p peer) tg.InputPeerClass {
	id, _ := strconv.ParseInt(p.ID, 10, 64)
	hash, _ := strconv.ParseInt(p.Hash, 10, 64)
	switch p.Kind {
	case "user":
		if id == c.userID {
			return &tg.InputPeerSelf{}
		}
		return &tg.InputPeerUser{UserID: id, AccessHash: hash}
	case "group":
		return &tg.InputPeerChat{ChatID: id}
	default:
		return &tg.InputPeerChannel{ChannelID: id, AccessHash: hash}
	}
}
func channelInput(p peer) *tg.InputChannel {
	id, _ := strconv.ParseInt(p.ID, 10, 64)
	hash, _ := strconv.ParseInt(p.Hash, 10, 64)
	return &tg.InputChannel{ChannelID: id, AccessHash: hash}
}
func (c *Client) opened(ctx context.Context, r request) error {
	var id int
	_ = json.Unmarshal(r.ID, &id)
	// Recheck eligibility before issuing intentional read/view acknowledgements.
	if _, e := c.message(ctx, r.Peer, id); e != nil {
		return e
	}
	if r.Peer.Kind == "channel" {
		if _, e := c.api.ChannelsReadMessageContents(ctx, &tg.ChannelsReadMessageContentsRequest{Channel: channelInput(r.Peer), ID: []int{id}}); e != nil {
			return e
		}
		if _, e := c.api.ChannelsReadHistory(ctx, &tg.ChannelsReadHistoryRequest{Channel: channelInput(r.Peer), MaxID: id}); e != nil {
			return e
		}
		_, e := c.api.MessagesGetMessagesViews(ctx, &tg.MessagesGetMessagesViewsRequest{Peer: c.input(r.Peer), ID: []int{id}, Increment: true})
		return e
	}
	if _, e := c.api.MessagesReadMessageContents(ctx, []int{id}); e != nil {
		return e
	}
	_, e := c.api.MessagesReadHistory(ctx, &tg.MessagesReadHistoryRequest{Peer: c.input(r.Peer), MaxID: id})
	return e
}
func (c *Client) getSponsored(ctx context.Context, p peer) (any, error) {
	v, e := c.api.MessagesGetSponsoredMessages(ctx, &tg.MessagesGetSponsoredMessagesRequest{Peer: c.input(p)})
	if e != nil {
		return nil, e
	}
	rows := []map[string]any{}
	if ads, ok := v.(*tg.MessagesSponsoredMessages); ok {
		for _, a := range ads.Messages {
			id := base64.RawURLEncoding.EncodeToString(a.RandomID)
			if len(id) == 0 || len(id) > 256 {
				continue
			}
			// Retain only a bounded active advertisement set; ids cannot authorize arbitrary RPC bytes.
			if len(c.sponsored) >= 256 {
				for k, b := range c.sponsored {
					clear(b)
					delete(c.sponsored, k)
				}
			}
			c.sponsored[id] = append([]byte(nil), a.RandomID...)
			rows = append(rows, map[string]any{"id": id, "title": a.Title, "text": a.Message, "url": a.URL, "button_text": a.ButtonText, "sponsor_info": a.SponsorInfo, "additional_info": a.AdditionalInfo, "min_display_duration": a.MinDisplayDuration, "max_display_duration": a.MaxDisplayDuration})
		}
	}
	return map[string]any{"messages": rows}, nil
}
func (c *Client) sponsoredAction(ctx context.Context, r request) error {
	var id string
	_ = json.Unmarshal(r.ID, &id)
	b, ok := c.sponsored[id]
	if !ok {
		return errors.New("SPONSORED_EXPIRED")
	}
	if r.Op == "sponsored_view" {
		_, e := c.api.MessagesViewSponsoredMessage(ctx, b)
		return e
	}
	_, e := c.api.MessagesClickSponsoredMessage(ctx, &tg.MessagesClickSponsoredMessageRequest{RandomID: b})
	return e
}
func samePeer(p peer, v tg.PeerClass) bool {
	id, _ := strconv.ParseInt(p.ID, 10, 64)
	switch q := v.(type) {
	case *tg.PeerUser:
		return p.Kind == "user" && id == q.UserID
	case *tg.PeerChat:
		return p.Kind == "group" && id == q.ChatID
	case *tg.PeerChannel:
		return p.Kind == "channel" && id == q.ChannelID
	}
	return false
}
func protected(p peer, chats []tg.ChatClass) bool {
	for _, v := range chats {
		switch q := v.(type) {
		case *tg.Channel:
			if p.Kind == "channel" && p.ID == strconv.FormatInt(q.ID, 10) {
				return q.Noforwards || q.Restricted
			}
		case *tg.Chat:
			if p.Kind == "group" && p.ID == strconv.FormatInt(q.ID, 10) {
				return q.Noforwards
			}
		}
	}
	return false
}
