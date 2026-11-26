package tgengine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tgerr"
	"math"
	"strings"
)

type request struct {
	Op       string          `json:"op"`
	Phone    string          `json:"phone"`
	Code     string          `json:"code"`
	Peer     peer            `json:"peer"`
	Folder   int             `json:"folder"`
	Offset   json.RawMessage `json:"offset"`
	Limit    int             `json:"limit"`
	Cursor   int             `json:"cursor"`
	ID       json.RawMessage `json:"id"`
	Revision string          `json:"revision"`
}

func parseRequest(raw string) (request, error) {
	var r request
	if len(raw) > 65536 || json.Unmarshal([]byte(raw), &r) != nil {
		return r, errors.New("BAD_REQUEST")
	}
	bad := false
	switch r.Op {
	case "status":
	case "login_phone":
		bad = len(r.Phone) < 7 || len(r.Phone) > 20
		for i, c := range r.Phone {
			if !(c >= '0' && c <= '9') && !(i == 0 && c == '+') {
				bad = true
			}
		}
	case "login_code":
		bad = len(r.Code) < 1 || len(r.Code) > 16
		for _, c := range r.Code {
			if c < '0' || c > '9' {
				bad = true
			}
		}
	case "chats":
		if r.Limit == 0 {
			r.Limit = 100
		}
		bad = r.Limit < 1 || r.Limit > 100 || r.Folder < 0 || r.Folder > 1
	case "page":
		if r.Limit == 0 {
			r.Limit = 50
		}
		bad = r.Limit < 1 || r.Limit > 50 || r.Cursor < -1 || int64(r.Cursor) > math.MaxInt32 || r.Peer.validate() != nil
	case "opened", "locate":
		var id int64
		bad = r.Peer.validate() != nil || json.Unmarshal(r.ID, &id) != nil || id < 1 || id > math.MaxInt32
		if r.Op == "locate" {
			bad = bad || len(r.Revision) != 64 || strings.Trim(r.Revision, "0123456789abcdef") != ""
		}
	case "sponsored":
		bad = r.Peer.validate() != nil || r.Peer.Kind != "channel"
	case "sponsored_view", "sponsored_click":
		var id string
		bad = json.Unmarshal(r.ID, &id) != nil || len(id) < 1 || len(id) > 256
	default:
		bad = true
	}
	if bad {
		return r, errors.New("BAD_REQUEST")
	}
	return r, nil
}
func safeError(e error) error {
	if e == nil {
		return nil
	}
	if errors.Is(e, context.DeadlineExceeded) {
		return errors.New("TIMEOUT")
	}
	if errors.Is(e, context.Canceled) {
		return errors.New("CLOSED")
	}
	if auth.IsUnauthorized(e) {
		return errors.New("AUTH_REQUIRED")
	}
	if errors.Is(e, auth.ErrPasswordInvalid) {
		return errors.New("PASSWORD_HASH_INVALID")
	}
	if rpc, ok := tgerr.As(e); ok {
		if rpc.Type == "FLOOD_WAIT" || rpc.Type == "FLOOD_PREMIUM_WAIT" {
			return fmt.Errorf("FLOOD_WAIT_%d", max(1, rpc.Argument))
		}
		switch rpc.Type {
		case "PHONE_NUMBER_INVALID", "PHONE_CODE_INVALID", "PHONE_CODE_EXPIRED", "PHONE_NUMBER_BANNED", "API_ID_INVALID", "API_ID_PUBLISHED_FLOOD", "PASSWORD_HASH_INVALID", "SESSION_PASSWORD_NEEDED", "PASSWORD_TOO_FRESH", "CHANNEL_PRIVATE", "CHAT_ADMIN_REQUIRED", "USER_BANNED_IN_CHANNEL", "MSG_ID_INVALID", "LOCATION_INVALID", "LIMIT_INVALID", "OFFSET_INVALID", "FILE_ID_INVALID", "AUTH_BYTES_INVALID", "DC_ID_INVALID", "CONNECTION_NOT_INITED", "CONNECTION_LAYER_INVALID", "LAYER_INVALID", "FILEREF_UPGRADE_NEEDED":
			return errors.New(rpc.Type)
		}
		if strings.HasPrefix(rpc.Type, "FILE_REFERENCE") {
			return errors.New("REFERENCE_EXPIRED")
		}
		return errors.New("RPC_ERROR")
	}
	switch e.Error() {
	case "BAD_REQUEST", "BAD_PEER", "BAD_RANGE", "BAD_LOCATOR", "STORE_ERROR", "AUTH_REQUIRED", "ACCOUNT_CHANGED", "LOGIN_REQUIRED", "CLIENT_CLOSED", "MEDIA_CHANGED", "MEDIA_UNAVAILABLE", "PROTECTED_CONTENT", "UNSUPPORTED_RESPONSE", "SPONSORED_EXPIRED", "SHORT_READ":
		return errors.New(e.Error())
	}
	return errors.New("NETWORK_ERROR")
}
