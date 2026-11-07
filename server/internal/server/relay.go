package server

import (
	"io"
	"net"
	"sync"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

const (
	ticketLifetime = 2 * time.Minute
	pairTimeout    = 75 * time.Second
	helloTimeout   = 15 * time.Second
)

// Relay pairs the two ends of a brokered connection by their 256-byte tickets
// and then splices their byte streams. Each end sends one framed
// {0x010001df:1, 0x0100032b:2, 0x090001ca:ticket}; both receive
// {0x010001df:2} once their partner has arrived.
type Relay struct {
	srv     *Server
	mu      sync.Mutex
	tickets map[string]*relayTicket
	active  map[net.Conn]struct{}
}

type relayTicket struct {
	pair    *relayPair
	side    int
	expires time.Time
}

type relayPair struct {
	cid     uint64
	mu      sync.Mutex
	waiting net.Conn
	matched chan struct{}
}

func newRelay(s *Server) *Relay {
	return &Relay{srv: s, tickets: map[string]*relayTicket{}, active: map[net.Conn]struct{}{}}
}

// issue creates one ticket per side for cid.
func (r *Relay) issue(cid uint64) ([]byte, []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	for k, t := range r.tickets {
		if now.After(t.expires) {
			delete(r.tickets, k)
		}
	}
	p := &relayPair{cid: cid}
	a, b := randomBytes(256), randomBytes(256)
	r.tickets[string(a)] = &relayTicket{pair: p, side: 0, expires: now.Add(ticketLifetime)}
	r.tickets[string(b)] = &relayTicket{pair: p, side: 1, expires: now.Add(ticketLifetime)}
	return a, b
}

func (r *Relay) track(c net.Conn, on bool) {
	r.mu.Lock()
	if on {
		r.active[c] = struct{}{}
	} else {
		delete(r.active, c)
	}
	r.mu.Unlock()
}

func (r *Relay) closeAll() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for c := range r.active {
		c.Close()
	}
}

func (r *Relay) handle(c net.Conn) {
	r.track(c, true)
	defer r.track(c, false)
	c.SetDeadline(time.Now().Add(helloTimeout))
	hello, err := wire.ReadFrame(c, 65536)
	if err != nil {
		c.Close()
		return
	}
	recs, err := wire.Parse(hello)
	var ticket []byte
	if err == nil {
		if kind, e := recs.U32(0x010001df); e == nil && kind == 1 {
			ticket, _ = recs.Field(0x090001ca)
		}
	}
	r.mu.Lock()
	t := r.tickets[string(ticket)]
	if t != nil {
		delete(r.tickets, string(ticket))
	}
	r.mu.Unlock()
	if t == nil || time.Now().After(t.expires) {
		r.srv.debugf("relay: rejected ticket from %s", c.RemoteAddr())
		wire.WriteFrame(c, wire.U32(0x010001df, 3))
		c.Close()
		return
	}
	p := t.pair
	c.SetDeadline(time.Time{})

	p.mu.Lock()
	if p.waiting == nil {
		matched := make(chan struct{})
		p.waiting, p.matched = c, matched
		p.mu.Unlock()
		r.srv.debugf("relay: cid=%d side %d waiting for partner", p.cid, t.side)
		select {
		case <-matched:
			return // the partner's goroutine now owns c
		case <-time.After(pairTimeout):
		}
		p.mu.Lock()
		if p.waiting == c {
			p.waiting = nil
			p.mu.Unlock()
			r.srv.debugf("relay: cid=%d pairing timed out", p.cid)
			c.Close()
			return
		}
		p.mu.Unlock()
		<-matched
		return
	}
	other := p.waiting
	p.waiting = nil
	close(p.matched)
	p.mu.Unlock()

	r.track(other, true)
	defer r.track(other, false)
	ack := wire.U32(0x010001df, 2)
	if wire.WriteFrame(other, ack) != nil || wire.WriteFrame(c, ack) != nil {
		other.Close()
		c.Close()
		return
	}
	r.srv.log.Printf("relay: cid=%d paired %s <-> %s", p.cid, other.RemoteAddr(), c.RemoteAddr())
	var wg sync.WaitGroup
	wg.Add(2)
	pipe := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(dst, src)
		dst.Close()
		src.Close()
	}
	go pipe(c, other)
	go pipe(other, c)
	wg.Wait()
	r.srv.debugf("relay: cid=%d closed", p.cid)
}
