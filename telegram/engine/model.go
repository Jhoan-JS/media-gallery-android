package tgengine

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"strconv"
)

type peer struct {
	Kind string `json:"kind"`
	ID   string `json:"id"`
	Hash string `json:"hash"`
}

func (p peer) validate() error {
	id, e := strconv.ParseInt(p.ID, 10, 64)
	if e != nil || id <= 0 {
		return errors.New("BAD_PEER")
	}
	switch p.Kind {
	case "group":
		return nil
	case "user", "channel":
		if _, e = strconv.ParseInt(p.Hash, 10, 64); e == nil {
			return nil
		}
	}
	return errors.New("BAD_PEER")
}

type locator struct {
	Peer      peer   `json:"peer"`
	MessageID int    `json:"message_id"`
	Kind      string `json:"kind"`
	ID        string `json:"id"`
	Hash      string `json:"hash"`
	Reference []byte `json:"reference"`
	DC        int    `json:"dc"`
	Size      int64  `json:"size"`
	MediaSize int64  `json:"media_size"`
	Thumb     string `json:"thumb,omitempty"`
	Revision  string `json:"revision"`
}

func (l locator) revision() string {
	n := l.MediaSize
	if n == 0 {
		n = l.Size
	}
	s := sha256.Sum256([]byte(fmt.Sprintf("tg-media-v1:%s:%s:%d:%s:%s:%d", l.Peer.Kind, l.Peer.ID, l.MessageID, l.Kind, l.ID, n)))
	return hex.EncodeToString(s[:])
}

type readRequest struct {
	Locator locator `json:"locator"`
	Offset  int64   `json:"offset"`
	Length  int     `json:"length"`
}

func (r readRequest) validate() error {
	if r.Offset < 0 || r.Length < 1 || r.Length > 524288 || r.Offset > math.MaxInt64-int64(r.Length) {
		return errors.New("BAD_RANGE")
	}
	return nil
}
func boundedIDs(ids []int, limit int, end bool) ([]int, int) {
	if len(ids) > limit {
		return ids[:limit], ids[limit-1]
	}
	if end {
		return ids, -1
	}
	if len(ids) == 0 {
		return ids, 0
	}
	return ids, ids[len(ids)-1]
}
func accountID(id int64) string {
	h := sha256.Sum256([]byte("local.ttlab.telegram.account.v1:" + strconv.FormatInt(id, 10)))
	return hex.EncodeToString(h[:])
}
