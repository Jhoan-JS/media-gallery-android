package tgengine

import (
	"context"
	"errors"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"testing"
	"time"
)

type memoryStore struct {
	data  []byte
	saves int
}

func (s *memoryStore) LoadSession() ([]byte, error) { return append([]byte(nil), s.data...), nil }
func (s *memoryStore) SaveSession(v []byte) error {
	s.data = append([]byte(nil), v...)
	s.saves++
	return nil
}
func TestSessionPersistsOnlyAfterAuthorization(t *testing.T) {
	disk := &memoryStore{}
	s := &sessionStore{store: disk}
	if e := s.StoreSession(context.Background(), []byte("transport-key")); e != nil {
		t.Fatal(e)
	}
	if disk.saves != 0 {
		t.Fatal("pending login persisted")
	}
	if e := s.authorize(); e != nil {
		t.Fatal(e)
	}
	if disk.saves != 1 || string(disk.data) != "transport-key" {
		t.Fatal("authorized session was not persisted")
	}
	if e := s.StoreSession(context.Background(), []byte("renewed-key")); e != nil || string(disk.data) != "renewed-key" {
		t.Fatal("session rotation lost")
	}
}
func TestErrorsNeverExposeRawServerOrStorageText(t *testing.T) {
	secret := "phone+123 token=private"
	if safeError(errors.New(secret)).Error() != "NETWORK_ERROR" {
		t.Fatal("raw error leaked")
	}
	if safeError(tgerr.New(420, "FLOOD_WAIT_37")).Error() != "FLOOD_WAIT_37" {
		t.Fatal("rate limit lost")
	}
	if safeError(context.DeadlineExceeded).Error() != "TIMEOUT" {
		t.Fatal("deadline lost")
	}
	if safeError(tgerr.New(401, "AUTH_KEY_UNREGISTERED")).Error() != "AUTH_REQUIRED" {
		t.Fatal("auth distinction lost")
	}
}
func TestMalformedInputsFailWithoutNetwork(t *testing.T) {
	for _, raw := range []string{`{}`, `{"op":"logout"}`, `{"op":"page","peer":{"kind":"channel","id":"1","hash":"2"},"limit":51}`, `{"op":"opened","peer":{"kind":"group","id":"1"},"id":2147483648}`} {
		if _, e := parseRequest(raw); e == nil {
			t.Fatalf("invalid request accepted: %s", raw)
		}
	}
}
func TestFileErrorsKeepOnlyDocumentedSafeCodes(t *testing.T){
    for _, code:=range []string{"LOCATION_INVALID","LIMIT_INVALID","OFFSET_INVALID","FILE_ID_INVALID","AUTH_BYTES_INVALID","DC_ID_INVALID","CONNECTION_NOT_INITED","CONNECTION_LAYER_INVALID","LAYER_INVALID","FILEREF_UPGRADE_NEEDED"}{if got:=safeError(tgerr.New(400,code)).Error();got!=code{t.Fatalf("%s became %s",code,got)}}
    if safeError(tgerr.New(400,"private_user_payload")).Error()!="RPC_ERROR"{t.Fatal("unknown server text exposed")}
}
func TestValidatedStatusUsesOnlyLifetimeIdentityCache(t *testing.T) {
	c := &Client{userID: 9007199254740993, state: "authorized"}
	// A nil transport proves repeated status cannot issue a network request.
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("cached status touched transport: %v", r)
		}
	}()
	s, e := c.status(context.Background())
	if e != nil || s.(map[string]any)["account"] != accountID(c.userID) {
		t.Fatalf("cached account changed: %v %v", s, e)
	}
}
func TestNewClientCannotTrustIdentityBeforeServerValidation(t *testing.T) {
	store := &memoryStore{}
	c, e := NewClient(1, "00000000000000000000000000000000", store)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	if c.userID != 0 || store.saves != 0 {
		t.Fatal("cold client trusted cached identity or persisted a pending login")
	}
}
func TestBrokeredClientCannotFallBackWhenDialerIsMissing(t *testing.T) {
	client, err := NewClientWithDialer(1, "00000000000000000000000000000000", &memoryStore{}, nil)
	if client != nil || err == nil || err.Error() != "BAD_REQUEST" {
		t.Fatalf("missing broker was accepted: %v", err)
	}
}
func TestRevokedAuthorizationInvalidatesIdentityAndLocators(t *testing.T) {
	c := &Client{userID: 17, state: "authorized", storage: &sessionStore{store: &memoryStore{}, authorized: true}, verified: map[string]verifiedMedia{"x": {Locator: locator{Reference: []byte{1}}, At: time.Now()}}}
	if c.failure(tgerr.New(401, "AUTH_KEY_UNREGISTERED")).Error() != "AUTH_REQUIRED" || c.userID != 0 || c.state != "phone" || len(c.verified) != 0 || c.storage.authorized {
		t.Fatal("revoked account was kept authorized")
	}
}
func TestRestrictionCacheExpiresAndRechecksNewOpen(t *testing.T) {
	c := &Client{verified: map[string]verifiedMedia{"new": {At: time.Now()}, "old": {At: time.Now().Add(-16 * time.Second)}}}
	if _, ok := c.cached("new", 1); !ok {
		t.Fatal("continuation missed hot cache")
	}
	if _, ok := c.cached("old", 1); ok {
		t.Fatal("expired restriction state reused")
	}
	if _, ok := c.cached("new", 0); ok {
		t.Fatal("new open did not revalidate")
	}
}

type brokenStore struct {
	memoryStore
	fail bool
}

func (s *brokenStore) SaveSession(b []byte) error {
	if s.fail {
		return errors.New("private storage detail")
	}
	return s.memoryStore.SaveSession(b)
}
func TestFailedSessionCommitRemainsRetryable(t *testing.T) {
	disk := &brokenStore{fail: true}
	s := &sessionStore{store: disk}
	_ = s.StoreSession(context.Background(), []byte("key"))
	if s.authorize() == nil {
		t.Fatal("broken store accepted")
	}
	disk.fail = false
	if e := s.authorize(); e != nil || disk.saves != 1 {
		t.Fatal("failed authorization commit was silently lost")
	}
}
func TestSelfIdentityIsStickyAndDisplayFieldsStayLocalToStatus(t *testing.T) {
	c := &Client{storage: &sessionStore{store: &memoryStore{}}}
	out, e := c.acceptSelf(&tg.User{ID: 17, AccessHash: 44, FirstName: "Test", LastName: "Account", Username: "synthetic_user", Phone: "must_not_be_exposed"})
	if e != nil {
		t.Fatal(e)
	}
	m := out.(map[string]any)
	if m["display_name"] != "Test Account" || m["username"] != "synthetic_user" || m["phone"] != nil {
		t.Fatalf("bad local identity projection: %+v", m)
	}
	c.userID = 0
	if _, e = c.acceptSelf(&tg.User{ID: 18}); e == nil || e.Error() != "ACCOUNT_CHANGED" {
		t.Fatal("client changed durable account identity")
	}
}
