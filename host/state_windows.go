//go:build windows

package main

import (
	"fmt"
	"unsafe"

	"golang.org/x/sys/windows"
)

var emptyBlobByte byte

func bytesToDataBlob(b []byte) *windows.DataBlob {
	if len(b) == 0 {
		return &windows.DataBlob{Data: &emptyBlobByte}
	}
	return &windows.DataBlob{
		Size: uint32(len(b)),
		Data: &b[0],
	}
}

func dpapiProtect(plaintext []byte) ([]byte, error) {
	var out windows.DataBlob
	if err := windows.CryptProtectData(
		bytesToDataBlob(plaintext),
		nil,
		nil,
		0,
		nil,
		windows.CRYPTPROTECT_UI_FORBIDDEN,
		&out,
	); err != nil {
		return nil, fmt.Errorf("dpapi protect: %w", err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))

	res := make([]byte, out.Size)
	copy(res, unsafe.Slice(out.Data, out.Size))
	return res, nil
}

func dpapiUnprotect(ciphertext []byte) ([]byte, error) {
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(
		bytesToDataBlob(ciphertext),
		nil,
		nil,
		0,
		nil,
		windows.CRYPTPROTECT_UI_FORBIDDEN,
		&out,
	); err != nil {
		return nil, fmt.Errorf("dpapi unprotect: %w", err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))

	res := make([]byte, out.Size)
	copy(res, unsafe.Slice(out.Data, out.Size))
	return res, nil
}
