using System;
using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace PptRunner.Desktop.QR;

public static class QrCodeService
{
    public static BitmapImage Generate(string payload)
    {
        using var qrGenerator = new QRCodeGenerator();
        using var qrCodeData =
            qrGenerator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);

        using var qrCode = new PngByteQRCode(qrCodeData);

        // 10 pixels per QR module gives us a sharp QR image.
        byte[] pngBytes = qrCode.GetGraphic(10);

        using var stream = new MemoryStream(pngBytes);

        var bitmap = new BitmapImage();

        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.StreamSource = stream;
        bitmap.EndInit();
        bitmap.Freeze();

        return bitmap;
    }
}