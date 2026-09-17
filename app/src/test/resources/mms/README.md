# Golden MMS PDUs

Expected parse is recorded **here**, from the spec and the published dump, **before**
the walker is treated as source of truth. If a test disagrees with this file, the
walker is wrong — not the fixture.

Fixtures are built by hand from OMA-TS-MMS-ENC and WAP-230-WSP. There is no
production encoder in this repo; a one-shot script wrote these bytes to match
the encodings below. Self-built ASCII blobs that a substring scanner would
accept are deliberately not used.

Well-known content-type octets (WSP Assigned Numbers, encoded `0x80|n`), matching
AOSP `WspTypeDecoder` / `PduContentTypes`:

| n    | encoded | type |
|------|---------|------|
| 0x03 | 0x83    | `text/plain` |
| 0x1D | 0x9D    | `image/gif` |
| 0x1E | 0x9E    | `image/jpeg` |
| 0x20 | 0xA0    | `image/png` |
| 0x23 | 0xA3    | `application/vnd.wap.multipart.mixed` |
| 0x33 | 0xB3    | `application/vnd.wap.multipart.related` |

Charset UTF-8 is IANA MIBEnum 106 = `0x6A`, encoded as a WSP short-integer `0xEA`.
Default for `text/plain` without a charset parameter is UTF-8, not Latin-1.

---

## `nowsms-retrieve-conf.bin`

**Provenance.** Headers reconstruct the public NowSMS M-Retrieve.conf dump posted
by Craig Dunn on 2003-10-01 and decoded by Todd Lucas:

https://support.nowsms.com/discus/messages/12/823.html

Published header bytes (as posted):

```
8c 84
98 31 77 37 37 34 51 6f 4b 75 74 45 50 39 66 7a 58 56 35 34 6e 62 41 20 00
8d 90
85 04 3d ef 78 cb
89 18 80 2b 33 35 37 39 39 35 33 36 32 31 34 2f 54 59 50 45 3d 50 4c 4d 4e 00
97 2b 34 34 37 37 34 30 33 30 35 31 31 35 2f 54 59 50 45 3d 50 4c 4d 4e 00
96 54 68 65 20 4d 61 74 72 69 78 00
86 81
90 81
8a 80
84 19 b3 89 61 70 70 6c 69 63 61 74 69 6f 6e 2f 73 6d 69 6c 00
```

Those 125 bytes match this file's prefix exactly, except the two PLMN addresses
which this repository replaces with documented synthetic numbers of the same
length (`+15550003621`, `+155500030511`) so the decoder test does not keep the
NowSMS dump's phone numbers. Header structure and remaining bytes are unchanged.

Todd noted that Content-Type's value-length `0x19` (25) is longer than the posted
remainder (`0xB3 0x89 application/smil\0` = 19 bytes). The original post was
truncated. This fixture completes the Content-Type with `Start=smil`
(`0x8A 73 6D 69 6C 00`) so the posted length is well-formed. That 6-byte
completion is **not** in the published dump; everything before it is.

**Body is not from NowSMS.** The dump stopped at Content-Type. The multipart
body is built from WAP-230-WSP §8.5: `nEntries=2`, a SMIL part to skip, and a
`text/plain` part with charset UTF-8 (`0x83 0x81 0xEA`) whose payload is
`Follow the white rabbit.`

**Expected parse**

| field | value |
|---|---|
| messageType | `0x84` M-Retrieve.conf |
| originator | `+15550003621` (`/TYPE=PLMN` stripped) |
| participants | `+155500030511` only — from the To header, not from the body |
| subject | `The Matrix` |
| transactionId | `1w774QoKutEP9fzXV54nbA ` (trailing space is in the dump) |
| body | `Follow the white rabbit.` |
| hasPhoto | `false` |
| hasAnyPart | `false` |
| parts | one `text/plain` part; SMIL skipped |

---

## `gm-wellknown.bin`

Google Messages / AOSP shape: `Content-Type` `0xB3` (multipart.related),
`text/plain` as `0x83`, `image/jpeg` as `0x9E`, plus a SMIL part to skip.

Headers: Message-Type retrieve-conf, Version 1.0, Transaction-Id `gm-txn-1`,
From `+15550001000/TYPE=PLMN`, To `+15550002000/TYPE=PLMN`, Subject `Photo`.

Body `nEntries=3`:

1. `application/smil` (extension-media text) + Content-ID `<smil>` — **skip**
2. `text/plain` general-form with charset `0xEA` (UTF-8); payload UTF-8 `café`
   (`63 61 66 C3 A9`)
3. `image/jpeg` constrained-media `0x9E`; minimal SOI/APP0/EOI JPEG

**Expected parse**

| field | value |
|---|---|
| messageType | `0x84` |
| originator | `+15550001000` |
| participants | `+15550002000` |
| subject | `Photo` |
| transactionId | `gm-txn-1` |
| body | `café` (must not be Latin-1 `cafÃ©`) |
| hasPhoto | `true` |
| hasAnyPart | `true` |
| parts | `text/plain` then `image/jpeg`; no SMIL |

---

## `picture-only.bin`

M-Retrieve.conf, From `+15550003000`, Content-Type constrained-media `0xA3`
(multipart.mixed), one `0x9E` JPEG part, no text.

**Expected parse**

| field | value |
|---|---|
| originator | `+15550003000` |
| participants | empty |
| body | `""` |
| hasPhoto | `true` |
| hasAnyPart | `true` |
| parts | one `image/jpeg` |

---

## `digits-in-image.bin`

M-Retrieve.conf, From `+15550009999`, To `+15550008888`, Content-Type `0xA3`,
one `0x9E` JPEG whose payload contains the ASCII bytes of `+15551234567`.

A whole-PDU substring scan would promote that number to a participant. Addresses
come only from From/To/Cc/Bcc, so it must not.

**Expected parse**

| field | value |
|---|---|
| originator | `+15550009999` |
| participants | `+15550008888` **only** — not `+15551234567` |
| hasPhoto | `true` |
| allAddresses | `+15550009999`, `+15550008888` |

---

## `notification.ind.bin`

M-Notification.ind (`0x8C 0x82`). No multipart body.

- From `+15550001111/TYPE=PLMN`
- Content-Location `http://mmsc.example/mms/xyz` (text-string)
- Transaction-Id `txn-abc`
- Expiry relative 86400 seconds (`0x88 0x05 0x81 0x03 0x01 0x51 0x80`)

**Expected parse**

| field | value |
|---|---|
| messageType | `0x82` |
| originator | `+15550001111` |
| contentLocation | `http://mmsc.example/mms/xyz` |
| transactionId | `txn-abc` |
| expiryMillis | `receivedAtMillis + 86_400_000` |
| body | `""` |
| parts | empty |
| hasPhoto | `false` |
| hasAnyPart | `false` |

Do not invent a photo bit on a notification. The PDU does not have one.

---

## `truncated.bin`

M-Retrieve.conf with From `+15550004444`, multipart.mixed, `nEntries=2`, a
complete `text/plain` "hi" part, then a JPEG part whose `DataLen` overruns the
file (8 bytes cut from a valid PDU).

**Expected parse:** must not throw. Originator `+15550004444` is in the headers;
the complete `text/plain` part yields body `"hi"`; the incomplete JPEG part is
dropped.
