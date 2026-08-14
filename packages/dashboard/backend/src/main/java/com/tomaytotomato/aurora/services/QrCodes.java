package com.tomaytotomato.aurora.services;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * QR encoding for the WireGuard peer onboarding flow — turns a client
 * {@code .conf} into the base64 PNG {@code VpnPeerSecret.qrPngBase64}
 * expects, for scanning straight into a phone's WireGuard app.
 *
 * <p>Uses {@code com.google.zxing:core} only (not the {@code javase}
 * module, which adds an AWT-based image writer dependency this class
 * doesn't need) — {@link BitMatrix} to {@link BufferedImage} conversion
 * and PNG encoding are both plain JDK ({@code java.awt.image}, {@code
 * javax.imageio}).
 */
final class QrCodes {

  private QrCodes() {}

  private static final int SIZE_PX = 320;

  /** Encode {@code text} as a QR code PNG, base64-encoded. */
  static String pngBase64(String text) {
    try {
      var writer = new QRCodeWriter();
      BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX,
          Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
      BufferedImage image = toImage(matrix);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (WriterException e) {
      // A .conf text is never too long for a QR code at error-correction
      // level M, but if zxing ever disagrees, that is a bug worth seeing
      // rather than swallowing into a blank image.
      throw new IllegalStateException("could not encode peer config as a QR code", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage toImage(BitMatrix matrix) {
    int width = matrix.getWidth();
    int height = matrix.getHeight();
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
      }
    }
    return image;
  }
}
