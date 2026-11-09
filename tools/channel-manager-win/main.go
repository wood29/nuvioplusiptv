// Nuvio Plus Channel Manager for Windows: a portable, single-file app.
//
// It finds the Nuvio Plus box on the home network, shows the Channel Manager in its own window
// (Edge or Chrome in app mode, else the default browser) and passes the page's requests to the
// box. Without the box (switched off, away from home) it keeps working from the last channel list
// it saw, and keeps changes on this PC until the box can take them.
package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

//go:embed channel_manager.html
var page []byte

const firstPort = 8096
const lastPort = 8105

var (
	mu       sync.Mutex
	box      string // host:port of the box, "" when not found
	device   string
	lastPing = time.Now()
	lastScan time.Time
	scanning bool
	dataDir  string
	client   = &http.Client{Timeout: 60 * time.Second}
)

type hello struct {
	App    string `json:"app"`
	Device string `json:"device"`
}

func file(name string) string { return filepath.Join(dataDir, name) }

func probe(addr string, timeout time.Duration) (string, bool) {
	c := &http.Client{Timeout: timeout}
	r, err := c.Get("http://" + addr + "/api/hello")
	if err != nil {
		return "", false
	}
	defer r.Body.Close()
	var h hello
	if json.NewDecoder(r.Body).Decode(&h) != nil || h.App != "Nuvio Plus" {
		return "", false
	}
	return h.Device, true
}

// discover looks for the box: the address used last time first, then every address on this PC's
// home networks (the usual /24) on the Channel Manager's ports.
func discover() {
	if b, err := os.ReadFile(file("NuvioPlus-box.txt")); err == nil {
		addr := strings.TrimSpace(string(b))
		if d, ok := probe(addr, 1500*time.Millisecond); ok {
			setBox(addr, d)
			return
		}
	}
	var prefixes []string
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil {
				continue
			}
			ip := ipn.IP.To4()
			if !(ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) || (ip[0] == 192 && ip[1] == 168)) {
				continue
			}
			prefixes = append(prefixes, fmt.Sprintf("%d.%d.%d.", ip[0], ip[1], ip[2]))
		}
	}
	type hit struct{ addr, dev string }
	found := make(chan hit, 1)
	var wg sync.WaitGroup
	sem := make(chan struct{}, 128)
	for _, p := range prefixes {
		for i := 1; i < 255; i++ {
			for port := firstPort; port <= lastPort; port++ {
				addr := fmt.Sprintf("%s%d:%d", p, i, port)
				wg.Add(1)
				go func() {
					defer wg.Done()
					sem <- struct{}{}
					defer func() { <-sem }()
					// Quick check that something listens there before asking it.
					conn, err := net.DialTimeout("tcp", addr, 350*time.Millisecond)
					if err != nil {
						return
					}
					conn.Close()
					if d, ok := probe(addr, 1500*time.Millisecond); ok {
						select {
						case found <- hit{addr, d}:
						default:
						}
					}
				}()
			}
		}
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case h := <-found:
		setBox(h.addr, h.dev)
	case <-done:
		select {
		case h := <-found:
			setBox(h.addr, h.dev)
		default:
			setBox("", "")
		}
	}
}

// rescanSoon looks for the box again in the background (at most every 8 seconds) while it's missing.
func rescanSoon() {
	mu.Lock()
	if box != "" || scanning || time.Since(lastScan) < 8*time.Second {
		mu.Unlock()
		return
	}
	scanning = true
	lastScan = time.Now()
	mu.Unlock()
	go func() {
		discover()
		mu.Lock()
		scanning = false
		mu.Unlock()
	}()
}

func setBox(addr, dev string) {
	mu.Lock()
	box, device = addr, dev
	mu.Unlock()
	if addr != "" {
		_ = os.WriteFile(file("NuvioPlus-box.txt"), []byte(addr), 0o644)
	}
}

func currentBox() string {
	mu.Lock()
	defer mu.Unlock()
	return box
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

func proxy(w http.ResponseWriter, r *http.Request, body []byte) ([]byte, int, error) {
	b := currentBox()
	if b == "" {
		return nil, 0, fmt.Errorf("no box")
	}
	req, _ := http.NewRequest(r.Method, "http://"+b+r.URL.Path, bytes.NewReader(body))
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := client.Do(req)
	if err != nil {
		setBox("", "")
		return nil, 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return data, resp.StatusCode, err
}

func pass(w http.ResponseWriter, data []byte, code int, contentType string) {
	if contentType == "" {
		contentType = "application/json; charset=utf-8"
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_, _ = w.Write(data)
}

func main() {
	exe, _ := os.Executable()
	dataDir = filepath.Dir(exe)
	// Portable: settings live next to the .exe; if that folder is read-only, in the user's AppData.
	if f, err := os.CreateTemp(dataDir, ".np-check"); err == nil {
		f.Close()
		os.Remove(f.Name())
	} else if d, err := os.UserConfigDir(); err == nil {
		dataDir = filepath.Join(d, "Nuvio Plus Channel Manager")
		_ = os.MkdirAll(dataDir, 0o755)
	}

	go discover()

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(page)
	})
	mux.HandleFunc("/api/local", func(w http.ResponseWriter, r *http.Request) {
		rescanSoon()
		mu.Lock()
		b, d := box, device
		mu.Unlock()
		_, qErr := os.Stat(file("NuvioPlus-pending.json"))
		_, cErr := os.Stat(file("NuvioPlus-channels.json"))
		writeJSON(w, map[string]any{"online": b != "", "box": b, "device": d, "queued": qErr == nil, "cached": cErr == nil})
	})
	mux.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		lastPing = time.Now()
		mu.Unlock()
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/rescan", func(w http.ResponseWriter, r *http.Request) {
		discover()
		mu.Lock()
		b, d := box, device
		mu.Unlock()
		writeJSON(w, map[string]any{"online": b != "", "box": b, "device": d})
	})
	// The address the TV shows (Settings → Live TV → Channels → Manage Channels on Your PC),
	// typed in by hand when the automatic search doesn't find it.
	mux.HandleFunc("/api/setBox", func(w http.ResponseWriter, r *http.Request) {
		addr := strings.TrimSpace(r.URL.Query().Get("addr"))
		addr = strings.TrimPrefix(strings.TrimPrefix(addr, "http://"), "https://")
		addr = strings.TrimSuffix(addr, "/")
		if addr != "" && !strings.Contains(addr, ":") {
			addr += fmt.Sprintf(":%d", firstPort)
		}
		if d, ok := probe(addr, 3*time.Second); ok {
			setBox(addr, d)
			writeJSON(w, map[string]any{"online": true, "box": addr, "device": d})
			return
		}
		writeJSON(w, map[string]any{"online": false, "box": "", "device": ""})
	})
	mux.HandleFunc("/api/state", func(w http.ResponseWriter, r *http.Request) {
		if data, code, err := proxy(w, r, nil); err == nil && code == 200 {
			_ = os.WriteFile(file("NuvioPlus-channels.json"), data, 0o644)
			pass(w, data, 200, "")
			return
		}
		// Offline: the channel list from last time.
		if data, err := os.ReadFile(file("NuvioPlus-channels.json")); err == nil {
			pass(w, data, 200, "")
			return
		}
		http.Error(w, "The TV wasn't found and there's no channel list saved on this PC yet. Open Nuvio Plus on the TV (with \"Allow the Channel Manager App\" on) and press Find TV.", 503)
	})
	mux.HandleFunc("/api/backup", func(w http.ResponseWriter, r *http.Request) {
		data, code, err := proxy(w, r, nil)
		if err != nil {
			http.Error(w, "The TV isn't reachable right now.", 503)
			return
		}
		w.Header().Set("Content-Disposition", "attachment; filename=\"nuvio-plus-live-tv-setup.json\"")
		pass(w, data, code, "")
	})
	propose := func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if data, code, err := proxy(w, r, body); err == nil {
			pass(w, data, code, "")
			return
		}
		// Offline: kept here, sent when the TV is reachable again.
		wrapped, _ := json.Marshal(map[string]any{"path": r.URL.Path, "body": json.RawMessage(body)})
		if err := os.WriteFile(file("NuvioPlus-pending.json"), wrapped, 0o644); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		writeJSON(w, map[string]any{"id": "queued"})
	}
	mux.HandleFunc("/api/apply", propose)
	mux.HandleFunc("/api/restore", propose)
	mux.HandleFunc("/api/sendQueued", func(w http.ResponseWriter, r *http.Request) {
		raw, err := os.ReadFile(file("NuvioPlus-pending.json"))
		if err != nil {
			http.Error(w, "Nothing waiting", 404)
			return
		}
		var q struct {
			Path string          `json:"path"`
			Body json.RawMessage `json:"body"`
		}
		if json.Unmarshal(raw, &q) != nil || (q.Path != "/api/apply" && q.Path != "/api/restore") {
			http.Error(w, "The saved changes couldn't be read", 500)
			return
		}
		req, _ := http.NewRequest("POST", q.Path, nil)
		data, code, err := proxy(w, req, q.Body)
		if err != nil {
			http.Error(w, "The TV isn't reachable right now.", 503)
			return
		}
		if code == 200 {
			_ = os.Remove(file("NuvioPlus-pending.json"))
		}
		pass(w, data, code, "")
	})
	mux.HandleFunc("/api/dropQueued", func(w http.ResponseWriter, r *http.Request) {
		_ = os.Remove(file("NuvioPlus-pending.json"))
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/status/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/queued") {
			writeJSON(w, map[string]any{"status": "queued"})
			return
		}
		data, code, err := proxy(w, r, nil)
		if err != nil {
			writeJSON(w, map[string]any{"status": "pending"})
			return
		}
		pass(w, data, code, "")
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	url := fmt.Sprintf("http://%s/", ln.Addr().String())
	go func() { _ = http.Serve(ln, mux) }()
	openWindow(url)

	// Closes itself once the window has been closed for a while (the page pings while open).
	for {
		time.Sleep(10 * time.Second)
		mu.Lock()
		idle := time.Since(lastPing)
		mu.Unlock()
		if idle > 90*time.Second {
			os.Exit(0)
		}
	}
}

func openWindow(url string) {
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("ProgramFiles"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("ProgramFiles"), `Google\Chrome\Application\chrome.exe`),
		filepath.Join(os.Getenv("ProgramFiles(x86)"), `Google\Chrome\Application\chrome.exe`),
		filepath.Join(os.Getenv("LocalAppData"), `Google\Chrome\Application\chrome.exe`),
	}
	profile := file("browser-profile")
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			cmd := exec.Command(c, "--app="+url, "--user-data-dir="+profile, "--window-size=1440,900", "--no-first-run")
			if cmd.Start() == nil {
				return
			}
		}
	}
	_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
}
