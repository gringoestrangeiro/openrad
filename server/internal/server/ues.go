package server

import (
	"encoding/binary"
	"fmt"
	"net"
)

// UES (UDP endpoint service): clients query two advertised hosts on a random
// port in 17301..17399 with 00 01 00 00 || transaction[16] and expect
// 01 01 00 0c || transaction || 00 01 00 08 00 01 port ipv4.
const (
	uesFirstPort = 17301
	uesLastPort  = 17399
)

func (s *Server) startUES() error {
	if len(s.cfg.UESHosts) == 0 {
		return nil
	}
	if len(s.cfg.UESHosts) < 2 {
		s.log.Printf("UES: one host configured; clients need two distinct hosts, so none are advertised")
	}
	bound := 0
	for _, ip := range s.cfg.UESHosts {
		for port := uesFirstPort; port <= uesLastPort; port++ {
			pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: ip.To4(), Port: port})
			if err != nil {
				return fmt.Errorf("UES bind %s:%d: %w", ip, port, err)
			}
			s.listeners = append(s.listeners, udpCloser{pc})
			bound++
			go s.serveUES(pc)
		}
	}
	s.log.Printf("UES: answering on %d UDP sockets for %v", bound, s.cfg.UESHosts)
	return nil
}

// udpCloser lets UDP sockets share the listener shutdown list.
type udpCloser struct{ *net.UDPConn }

func (u udpCloser) Accept() (net.Conn, error) { return nil, net.ErrClosed }
func (u udpCloser) Addr() net.Addr            { return u.LocalAddr() }

func (s *Server) serveUES(pc *net.UDPConn) {
	buf := make([]byte, 512)
	for {
		n, src, err := pc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		ip := src.IP.To4()
		if n != 20 || ip == nil || buf[0] != 0 || buf[1] != 1 || buf[2] != 0 || buf[3] != 0 {
			continue
		}
		resp := make([]byte, 32)
		resp[0], resp[1], resp[3] = 1, 1, 12
		copy(resp[4:20], buf[4:20])
		resp[21], resp[23], resp[25] = 1, 8, 1
		binary.BigEndian.PutUint16(resp[26:], uint16(src.Port))
		copy(resp[28:], ip)
		pc.WriteToUDP(resp, src)
	}
}
