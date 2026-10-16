package dev.codexlan.remote;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

final class PairingQr {
    static String grayscale(byte[] frame,int width,int height) throws Exception {
        if(width<1||height<1||(long)width*height>frame.length)throw new Exception("无效图像");
        return decode(new PlanarYUVLuminanceSource(frame,width,height,0,0,width,height,false));
    }
    static String pixels(int[] pixels,int width,int height) throws Exception {return decode(new RGBLuminanceSource(width,height,pixels));}
    private static String decode(LuminanceSource source) throws Exception {
        QRCodeReader reader=new QRCodeReader();try{return reader.decode(new BinaryBitmap(new HybridBinarizer(source))).getText();}
        catch(com.google.zxing.ReaderException e){reader.reset();return reader.decode(new BinaryBitmap(new HybridBinarizer(source.invert()))).getText();}
        finally{reader.reset();}
    }
}
