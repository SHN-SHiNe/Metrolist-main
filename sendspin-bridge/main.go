package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	ss "github.com/Sendspin/sendspin-go/pkg/sendspin"
)

const (
	sampleRate = 48000
	channels   = 2
)

type track struct {
	ID         string `json:"id"`
	URL        string `json:"url"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Album      string `json:"album"`
	ArtworkURL string `json:"artworkUrl"`
	DurationMS int64  `json:"durationMs"`
}

type roomRequest struct {
	Name           string  `json:"name"`
	Queue          []track `json:"queue"`
	CurrentTrackID string  `json:"currentTrackId"`
	PositionMS     int64   `json:"positionMs"`
	Playing        bool    `json:"playing"`
	ForcePosition  bool    `json:"forcePosition"`
}

type roomStatus struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Port           int    `json:"port"`
	MemberCount    int    `json:"memberCount"`
	CurrentTrackID string `json:"currentTrackId"`
	PositionMS     int64  `json:"positionMs"`
	Playing        bool   `json:"playing"`
	Revision       int64  `json:"revision"`
}

type bridge struct {
	mu       sync.RWMutex
	rooms    map[string]*room
	eventURL string
}

type room struct {
	mu         sync.Mutex
	id         string
	name       string
	server     *ss.Server
	source     *switchingSource
	queue      []track
	index      int
	position   int64
	playing    bool
	startedAt  time.Time
	eventURL   string
	eventToken string
	revision   int64
}

func main() {
	address := env("SHINE_SENDSPIN_CONTROL_ADDR", "127.0.0.1:8936")
	b := &bridge{rooms: make(map[string]*room), eventURL: os.Getenv("SHINE_SENDSPIN_EVENT_URL")}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("/rooms/", b.handleRoom)
	server := &http.Server{Addr: address, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	log.Printf("SHiNe Sendspin bridge listening on %s", address)
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func (b *bridge) handleRoom(w http.ResponseWriter, req *http.Request) {
	id := strings.Trim(strings.TrimPrefix(req.URL.Path, "/rooms/"), "/")
	if id == "" || strings.Contains(id, "/") {
		http.Error(w, "invalid room id", http.StatusBadRequest)
		return
	}
	switch req.Method {
	case http.MethodGet:
		b.mu.RLock()
		r := b.rooms[id]
		b.mu.RUnlock()
		if r == nil {
			http.Error(w, "room not found", http.StatusNotFound)
			return
		}
		writeJSON(w, http.StatusOK, r.status())
	case http.MethodPut:
		var value roomRequest
		if err := json.NewDecoder(req.Body).Decode(&value); err != nil {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		status, err := b.upsert(id, value)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, status)
	case http.MethodDelete:
		b.mu.Lock()
		r := b.rooms[id]
		delete(b.rooms, id)
		b.mu.Unlock()
		if r != nil {
			r.server.Stop()
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (b *bridge) upsert(id string, value roomRequest) (roomStatus, error) {
	b.mu.RLock()
	r := b.rooms[id]
	b.mu.RUnlock()
	if r == nil {
		var err error
		r, err = newRoom(id, value.Name, b.eventURL, os.Getenv("SHINE_SENDSPIN_INTERNAL_TOKEN"))
		if err != nil {
			return roomStatus{}, err
		}
		b.mu.Lock()
		if existing := b.rooms[id]; existing != nil {
			r.server.Stop()
			r = existing
		} else {
			b.rooms[id] = r
		}
		b.mu.Unlock()
	}
	return r.apply(value)
}

func newRoom(id, name, eventURL, eventToken string) (*room, error) {
	source := newSwitchingSource()
	r := &room{id: id, name: name, source: source, index: -1, eventURL: eventURL, eventToken: eventToken}
	source.onEnd = func() { r.control("next") }
	server, err := ss.NewServer(ss.ServerConfig{
		Port: 0, Name: "SHiNe · " + name, Source: source, EnableMDNS: false,
		SupportedRoles: []string{"player", "metadata", "controller"},
	})
	if err != nil {
		return nil, err
	}
	r.server = server
	server.Group().RegisterRole(ss.NewControllerRole(ss.ControllerConfig{
		SupportedCommands: []string{"play", "pause", "next", "previous"},
		OnCommand:         func(_ *ss.ServerClient, command string) { r.control(command) },
	}))
	go func() {
		if err := server.Start(); err != nil {
			log.Printf("Sendspin room %s stopped: %v", id, err)
		}
	}()
	deadline := time.Now().Add(3 * time.Second)
	for server.Addr() == nil && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if server.Addr() == nil {
		server.Stop()
		return nil, errors.New("sendspin server did not bind")
	}
	return r, nil
}

func (r *room) apply(value roomRequest) (roomStatus, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.name = value.Name
	r.queue = append([]track(nil), value.Queue...)
	next := indexOf(r.queue, value.CurrentTrackID)
	trackChanged := next != r.index || value.CurrentTrackID != r.source.trackID()
	positionChanged := value.ForcePosition
	r.index = next
	if trackChanged || positionChanged {
		r.position = max(value.PositionMS, 0)
		r.startedAt = time.Now()
		if next >= 0 {
			if err := r.source.setTrack(r.queue[next], r.position); err != nil {
				return roomStatus{}, err
			}
			r.broadcastMetadata()
		} else {
			r.source.clear()
		}
	}
	if value.Playing != r.playing {
		if value.Playing {
			r.startedAt = time.Now()
		} else {
			r.position = r.currentPositionLocked()
		}
		r.playing = value.Playing
		r.source.setPlaying(value.Playing)
	}
	if r.playing {
		r.server.Group().SetPlaybackState("playing")
	} else {
		r.server.Group().SetPlaybackState("stopped")
	}
	r.revision = time.Now().UnixNano()
	return r.statusLocked(), nil
}

func (r *room) control(command string) {
	r.mu.Lock()
	switch command {
	case "play":
		if !r.playing {
			r.startedAt = time.Now()
		}
		r.playing = true
	case "pause":
		if r.playing {
			r.position = r.currentPositionLocked()
		}
		r.playing = false
	case "next":
		if len(r.queue) > 0 {
			r.index = (r.index + 1) % len(r.queue)
			r.position = 0
			r.startedAt = time.Now()
			if err := r.source.setTrack(r.queue[r.index], 0); err != nil {
				log.Printf("failed to start next track in room %s: %v", r.id, err)
				r.playing = false
				r.source.clear()
			} else {
				r.broadcastMetadata()
			}
		}
	case "previous":
		if len(r.queue) > 0 {
			r.index = (r.index - 1 + len(r.queue)) % len(r.queue)
			r.position = 0
			r.startedAt = time.Now()
			if err := r.source.setTrack(r.queue[r.index], 0); err != nil {
				log.Printf("failed to start previous track in room %s: %v", r.id, err)
				r.playing = false
				r.source.clear()
			} else {
				r.broadcastMetadata()
			}
		}
	}
	r.source.setPlaying(r.playing)
	if r.playing {
		r.server.Group().SetPlaybackState("playing")
	} else {
		r.server.Group().SetPlaybackState("stopped")
	}
	r.revision = time.Now().UnixNano()
	r.mu.Unlock()
	r.notifyState()
}

func (r *room) broadcastMetadata() {
	if role, ok := r.server.Group().GetRole("metadata").(*ss.MetadataGroupRole); ok {
		role.BroadcastMetadata()
	}
}

func (r *room) notifyState() {
	if r.eventURL == "" {
		return
	}
	status := r.status()
	body, err := json.Marshal(status)
	if err != nil {
		return
	}
	go func() {
		var lastError error
		for attempt := 0; attempt < 5; attempt++ {
			request, err := http.NewRequest(http.MethodPost, r.eventURL, strings.NewReader(string(body)))
			if err != nil {
				return
			}
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("X-SHiNe-Internal-Token", r.eventToken)
			client := &http.Client{Timeout: 3 * time.Second}
			response, err := client.Do(request)
			if err != nil {
				lastError = err
			} else {
				_ = response.Body.Close()
				if response.StatusCode >= 200 && response.StatusCode < 300 {
					return
				}
				lastError = fmt.Errorf("HTTP %d", response.StatusCode)
			}
			if attempt < 4 {
				time.Sleep(time.Duration(attempt+1) * 250 * time.Millisecond)
			}
		}
		log.Printf("failed to persist Sendspin room %s state after retries: %v", r.id, lastError)
	}()
}

func (r *room) currentPositionLocked() int64 {
	if !r.playing || r.startedAt.IsZero() {
		return r.position
	}
	return r.position + time.Since(r.startedAt).Milliseconds()
}

func (r *room) status() roomStatus {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.statusLocked()
}

func (r *room) statusLocked() roomStatus {
	port := 0
	if address, ok := r.server.Addr().(*net.TCPAddr); ok {
		port = address.Port
	}
	currentID := ""
	if r.index >= 0 && r.index < len(r.queue) {
		currentID = r.queue[r.index].ID
	}
	return roomStatus{r.id, r.name, port, len(r.server.Clients()), currentID, r.currentPositionLocked(), r.playing, r.revision}
}

type switchingSource struct {
	mu      sync.Mutex
	track   track
	playing bool
	process *exec.Cmd
	reader  io.ReadCloser
	onEnd   func()
}

func newSwitchingSource() *switchingSource { return &switchingSource{} }
func (s *switchingSource) SampleRate() int { return sampleRate }
func (s *switchingSource) Channels() int   { return channels }
func (s *switchingSource) Metadata() (string, string, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.track.Title, s.track.Artist, s.track.Album
}
func (s *switchingSource) trackID() string { s.mu.Lock(); defer s.mu.Unlock(); return s.track.ID }

func (s *switchingSource) Read(samples []int32) (int, error) {
	s.mu.Lock()
	if !s.playing || s.reader == nil {
		s.mu.Unlock()
		for i := range samples {
			samples[i] = 0
		}
		return len(samples), nil
	}
	reader := s.reader
	s.mu.Unlock()
	buf := make([]byte, len(samples)*3)
	n, err := io.ReadFull(reader, buf)
	count := n / 3
	for i := 0; i < count; i++ {
		value := int32(buf[i*3]) | int32(buf[i*3+1])<<8 | int32(buf[i*3+2])<<16
		if value&0x800000 != 0 {
			value |= ^int32(0xffffff)
		}
		samples[i] = value
	}
	for i := count; i < len(samples); i++ {
		samples[i] = 0
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		s.mu.Lock()
		endedCurrent := s.reader == reader
		if endedCurrent {
			s.stopLocked()
		}
		onEnd := s.onEnd
		s.mu.Unlock()
		if endedCurrent && onEnd != nil {
			go onEnd()
		}
		return len(samples), nil
	}
	if err != nil {
		return count, err
	}
	return count, nil
}

func (s *switchingSource) setTrack(value track, positionMS int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stopLocked()
	s.track = value
	if value.URL == "" {
		return nil
	}
	args := []string{"-nostdin", "-loglevel", "error", "-ss", fmt.Sprintf("%.3f", float64(positionMS)/1000), "-i", value.URL, "-vn", "-f", "s24le", "-acodec", "pcm_s24le", "-ar", strconv.Itoa(sampleRate), "-ac", strconv.Itoa(channels), "pipe:1"}
	cmd := exec.Command(env("SHINE_FFMPEG_PATH", "ffmpeg"), args...)
	reader, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	s.process, s.reader = cmd, reader
	return nil
}

func (s *switchingSource) setPlaying(value bool) { s.mu.Lock(); s.playing = value; s.mu.Unlock() }
func (s *switchingSource) clear() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stopLocked()
	s.track = track{}
}
func (s *switchingSource) Close() error { s.clear(); return nil }
func (s *switchingSource) stopLocked() {
	if s.reader != nil {
		_ = s.reader.Close()
	}
	if s.process != nil && s.process.Process != nil {
		_ = s.process.Process.Kill()
		_, _ = s.process.Process.Wait()
	}
	s.reader, s.process = nil, nil
}

func indexOf(queue []track, id string) int {
	for i := range queue {
		if queue[i].ID == id {
			return i
		}
	}
	return -1
}
func max(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
