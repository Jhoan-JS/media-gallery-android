package tgengine

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/gotd/td/session"
	"sync"
)

// Store is implemented by Android using authenticated encrypted internal storage.
// Empty LoadSession means there is no session. Never return a filesystem path.
type Store interface {
	LoadSession() ([]byte, error)
	SaveSession([]byte) error
}
type sessionStore struct {
	store      Store
	mu         sync.Mutex
	authorized bool
	pending    []byte
}
func(s *sessionStore) primaryDC()int{
    s.mu.Lock();defer s.mu.Unlock()
    var saved struct{Version int;Data struct{DC int}}
    if json.Unmarshal(s.pending,&saved)!=nil||saved.Version!=1{return 0};return saved.Data.DC
}

func (s *sessionStore) StoreSession(_ context.Context, b []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clear(s.pending)
	s.pending = append(s.pending[:0], b...)
	if s.authorized {
		if e := s.persist(); e != nil {
			s.authorized = false
			return e
		}
	}
	return nil
}
func (s *sessionStore) LoadSession(_ context.Context) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, e := s.store.LoadSession()
	if e != nil {
		return nil, errors.New("STORE_ERROR")
	}
	if len(b) == 0 {
		return nil, session.ErrNotFound
	}
	if len(b) > 1048576 {
		clear(b)
		return nil, errors.New("STORE_ERROR")
	}
	s.pending = append(s.pending[:0], b...)
	return b, nil
}
func (s *sessionStore) persist() error {
	if len(s.pending) == 0 {
		return nil
	}
	b := append([]byte(nil), s.pending...)
	defer clear(b)
	if e := s.store.SaveSession(b); e != nil {
		return errors.New("STORE_ERROR")
	}
	return nil
}
func (s *sessionStore) authorize() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.authorized {
		return nil
	}
	if e := s.persist(); e != nil {
		return e
	}
	s.authorized = true
	return nil
}
func (s *sessionStore) wipe() { s.mu.Lock(); defer s.mu.Unlock(); clear(s.pending); s.pending = nil }
