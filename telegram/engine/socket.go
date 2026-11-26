package tgengine

import (
	"context"
	"errors"
	"net"
	"os"
	"runtime"

	"github.com/gotd/td/telegram/dcs"
)

// SocketDialer opens connected TCP sockets for Android. Calls may overlap.
type SocketDialer interface {
	// OpenTelegramSocket connects to address (host:port) with a bounded timeout.
	// A successful call transfers a detached descriptor to Go. On error the
	// implementation closes any descriptors it opened.
	OpenTelegramSocket(address string) (int, error)
}

func brokeredResolver(dialer SocketDialer) dcs.Resolver {
	return dcs.Plain(dcs.PlainOptions{Dial: func(ctx context.Context, _, address string) (net.Conn, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if runtime.GOOS != "android" && runtime.GOOS != "linux" {
			return nil, errors.New("brokered socket descriptors require Android or Linux")
		}
		fd, err := dialer.OpenTelegramSocket(address)
		if err != nil {
			return nil, err
		}
		file := os.NewFile(uintptr(fd), "telegram-broker-socket")
		if file == nil {
			return nil, errors.New("broker returned no socket descriptor")
		}
		// FileConn duplicates the descriptor. Its connection owns that copy.
		defer file.Close()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		conn, err := net.FileConn(file)
		if err != nil {
			return nil, err
		}
		tcp, ok := conn.(*net.TCPConn)
		if !ok || tcp.RemoteAddr() == nil {
			_ = conn.Close()
			return nil, errors.New("broker did not return a connected TCP socket")
		}
		if err := ctx.Err(); err != nil {
			_ = tcp.Close()
			return nil, err
		}
		// gotd replaces these deadlines per operation after the transport handshake.
		if deadline, ok := ctx.Deadline(); ok {
			if err := tcp.SetDeadline(deadline); err != nil {
				_ = tcp.Close()
				return nil, err
			}
		}
		return tcp, nil
	}})
}
