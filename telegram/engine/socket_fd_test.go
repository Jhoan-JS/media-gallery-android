//go:build linux

package tgengine

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"syscall"
	"testing"
	"time"

	"github.com/gotd/td/telegram/dcs"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/transport"
)

type testSocketDialer func(string) (int, error)

func (dial testSocketDialer) OpenTelegramSocket(address string) (int, error) {
	return dial(address)
}

func tcpFixture(t *testing.T) (*net.TCPListener, tg.DCOption) {
	t.Helper()
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	if err := listener.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	return listener, tg.DCOption{ID: 1, IPAddress: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port}
}

func detachFixtureSocket(t *testing.T, ctx context.Context, address string) int {
	t.Helper()
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp4", address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	raw, err := conn.(*net.TCPConn).SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var fd int
	var dupErr error
	if err := raw.Control(func(value uintptr) { fd, dupErr = syscall.Dup(int(value)) }); err != nil {
		t.Fatal(err)
	}
	if dupErr != nil {
		t.Fatal(dupErr)
	}
	return fd
}

func TestBrokerSuppliesSocketForEveryDCPath(t *testing.T) {
	for _, mode := range []string{"primary", "media", "cdn"} {
		t.Run(mode, func(t *testing.T) {
			listener, option := tcpFixture(t)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			fd := -1
			resolver := brokeredResolver(testSocketDialer(func(address string) (int, error) {
				if address != listener.Addr().String() {
					t.Fatalf("got broker address %q, want %q", address, listener.Addr())
				}
				fd = detachFixtureSocket(t, ctx, address)
				return fd, nil
			}))
			var dial func(context.Context, int, dcs.List) (transport.Conn, error)
			switch mode {
			case "primary":
				dial = resolver.Primary
			case "media":
				dial = resolver.MediaOnly
				option.MediaOnly = true
			case "cdn":
				dial = resolver.CDN
				option.CDN = true
			}
			conn, err := dial(ctx, 1, dcs.List{Options: []tg.DCOption{option}})
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if fd < 0 || !errors.Is(syscall.Fstat(fd, &syscall.Stat_t{}), syscall.EBADF) {
				t.Fatal("broker descriptor was not transferred and closed")
			}
			server, err := listener.AcceptTCP()
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			if err := server.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
				t.Fatal(err)
			}
			header := make([]byte, 4)
			if _, err := io.ReadFull(server, header); err != nil || !bytes.Equal(header, []byte{0xee, 0xee, 0xee, 0xee}) {
				t.Fatalf("MTProto intermediate header = %x, err = %v", header, err)
			}
		})
	}
}

func TestBrokerFailureHasNoDirectNetworkFallback(t *testing.T) {
	_, option := tcpFixture(t)
	fixtureErr := errors.New("fixture broker rejected socket")
	resolver := brokeredResolver(testSocketDialer(func(string) (int, error) { return -1, fixtureErr }))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := resolver.Primary(ctx, 1, dcs.List{Options: []tg.DCOption{option}})
	if conn != nil {
		_ = conn.Close()
	}
	if !errors.Is(err, fixtureErr) {
		t.Fatalf("broker rejection was not returned: %v", err)
	}
}

func TestBrokerClosesDescriptorReceivedAfterCancellation(t *testing.T) {
	listener, option := tcpFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	fd := -1
	resolver := brokeredResolver(testSocketDialer(func(address string) (int, error) {
		fd = detachFixtureSocket(t, ctx, address)
		cancel()
		return fd, nil
	}))
	conn, err := resolver.Primary(ctx, 1, dcs.List{Options: []tg.DCOption{option}})
	if conn != nil {
		_ = conn.Close()
	}
	if !errors.Is(err, context.Canceled) || fd < 0 || !errors.Is(syscall.Fstat(fd, &syscall.Stat_t{}), syscall.EBADF) {
		t.Fatalf("canceled dial did not close the descriptor: %v", err)
	}
	server, err := listener.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if err := server.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if n, err := server.Read(make([]byte, 1)); n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("canceled dial leaked a connection or wrote a header: %d bytes, %v", n, err)
	}
}
