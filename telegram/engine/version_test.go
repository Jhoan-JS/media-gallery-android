package tgengine

import "testing"

func TestEngineVersionIsPinnedBuildMetadata(t *testing.T) {
	if EngineVersion() != "tgengine/1.0 gotd/td/v0.162.0" {
		t.Fatal("native engine version does not identify the pinned implementation")
	}
}
