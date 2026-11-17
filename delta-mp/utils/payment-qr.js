import QRCode from 'qrcode'

// P0 payment invariant: data-URL QR encoders require literal colors, not CSS tokens.
// Keep the standard four-module quiet zone and fixed black-on-white in every theme.
export function createPaymentQr(url) {
  return QRCode.toDataURL(url, {
    width: 560,
    margin: 4,
    errorCorrectionLevel: 'M',
    color: { dark: '#111111ff', light: '#ffffffff' }
  })
}
