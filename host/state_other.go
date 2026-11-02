//go:build !windows

package main

import "errors"

var errDPAPINotSupported = errors.New("not supported yet")

func dpapiProtect([]byte) ([]byte, error) {
	return nil, errDPAPINotSupported
}

func dpapiUnprotect([]byte) ([]byte, error) {
	return nil, errDPAPINotSupported
}
