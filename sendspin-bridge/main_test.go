package main

import "testing"

func TestIndexOf(t *testing.T) {
	queue := []track{{ID: "one"}, {ID: "two"}}
	if got := indexOf(queue, "two"); got != 1 {
		t.Fatalf("indexOf() = %d, want 1", got)
	}
	if got := indexOf(queue, "missing"); got != -1 {
		t.Fatalf("indexOf() = %d, want -1", got)
	}
}

func TestRoomPositionAdvancesOnlyWhilePlaying(t *testing.T) {
	r := &room{position: 1250, playing: false}
	if got := r.currentPositionLocked(); got != 1250 {
		t.Fatalf("position = %d, want 1250", got)
	}
}
