package tgengine

import (
	"encoding/json"
	"testing"
)

func TestPeerPreserves64BitIdentityAndRejectsMissingHash(t *testing.T) {
	p := peer{Kind: "channel", ID: "9007199254740993", Hash: "-9223372036854775807"}
	if err := p.validate(); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(p)
	var copy peer
	if err := json.Unmarshal(raw, &copy); err != nil || copy != p {
		t.Fatal("peer identity was truncated")
	}
	p.Hash = ""
	if p.validate() == nil {
		t.Fatal("channel without access hash accepted")
	}
}

func TestRangeIsBoundedAndCannotOverflow(t *testing.T) {
	for _, v := range []readRequest{{Offset: -1, Length: 1}, {Offset: 0, Length: 0}, {Offset: 0, Length: 524289}, {Offset: 9223372036854775807, Length: 1}} {
		if v.validate() == nil {
			t.Fatalf("invalid range accepted: %+v", v)
		}
	}
	if err := (readRequest{Offset: 17, Length: 524288}).validate(); err != nil {
		t.Fatal(err)
	}
}

func TestRevisionExcludesRenewableReferenceButIncludesMediaIdentity(t *testing.T) {
	a := locator{Peer: peer{Kind: "channel", ID: "1", Hash: "2"}, MessageID: 17, Kind: "document", ID: "9007199254740993", Hash: "4", Size: 99, DC: 2, Reference: []byte{1}}
	r := a.revision()
	a.Reference = []byte{2, 3}
	a.Hash = "42"
	if a.revision() != r {
		t.Fatal("renewed reference changed revision")
	}
	a.ID = "9007199254740994"
	if a.revision() == r {
		t.Fatal("different media retained revision")
	}
}

func TestHistoryCursorNeverSkipsUnreturnedMedia(t *testing.T) {
	ids := []int{100, 99, 98, 97, 96}
	got, next := boundedIDs(ids, 2, false)
	if len(got) != 2 || got[0] != 100 || got[1] != 99 || next != 99 {
		t.Fatalf("bad cursor: %v %d", got, next)
	}
	got, next = boundedIDs(ids, 50, true)
	if len(got) != 5 || next != -1 {
		t.Fatalf("bad end: %v %d", got, next)
	}
}

func TestAccountIdentityIsOpaqueAndStable(t *testing.T) {
	if accountID(17) == accountID(18) || len(accountID(17)) != 64 || accountID(17) != accountID(17) {
		t.Fatal("invalid account identity")
	}
}

func TestMediaIDsKeepTheFullSignedTLDomain(t *testing.T) {
    for _, kind := range []string{"photo", "document"} {
        l := locator{Peer: peer{Kind:"channel", ID:"17", Hash:"-9223372036854775808"}, MessageID:1, Kind:kind, ID:"-9223372036854775808", Hash:"-9223372036854775808", DC:2, Size:128, MediaSize:128, Reference:[]byte{1}}
        l.Revision=l.revision()
        if err:=l.validate(); err!=nil { t.Fatalf("signed %s identity rejected: %v",kind,err) }
        l.ID="0";l.Revision=l.revision()
        if l.validate()==nil { t.Fatal("zero media identity accepted") }
    }
}
