package com.surprising.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import static com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage;

/**
 * 自定义生成二维码
 *
 * @author admin
 */
public class QrCodeUtil {
    private static final String CHARSET = "utf-8";

    private static BufferedImage format(String content) throws Exception {
        int qrcodeSize = 200;
        Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.CHARACTER_SET, CHARSET);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(content,
                BarcodeFormat.QR_CODE, qrcodeSize, qrcodeSize, hints);
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF000000
                        : 0xFFFFFFFF);
            }
        }

        return image;
    }

    public static String base64(String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BufferedImage image = format(content);
            ImageIO.write(image, "png", out);
        } catch (Exception e) {
            return "";
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 生成二维码并使用Base64编码
     */
    public static String qrCode(String content) throws Exception {
        MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
        Map hints = new HashMap();

        //设置二维码四周白色区域的大小
        hints.put(EncodeHintType.MARGIN, 1);
        //设置二维码的容错性
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        //画二维码
        BitMatrix bitMatrix = multiFormatWriter.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        BufferedImage image = toBufferedImage(bitMatrix);
        //注意此处拿到字节数据
        byte[] bytes = imageToBytes(image, "jpg");
        //Base64编码
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] imageToBytes(BufferedImage image, String jpg) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, jpg, baos);
            baos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new Exception();
        }
    }
}