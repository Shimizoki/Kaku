package ca.fuwafuwa.kaku.Ocr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.os.Message;
import android.util.Log;
import android.util.Pair;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import ca.fuwafuwa.kaku.Interfaces.Stoppable;
import ca.fuwafuwa.kaku.MainService;
import ca.fuwafuwa.kaku.R;
import ca.fuwafuwa.kaku.Windows.CaptureWindow;

/**
 * Created by Xyresic on 4/16/2016.
 */
public class OcrRunnable implements Runnable, Stoppable {

    private static final String TAG = OcrRunnable.class.getName();

    private MainService mContext;
    private CaptureWindow mCaptureWindow;
    private TessBaseAPI mTessBaseAPI;
    private boolean mRunning = true;
    private BoxParams mBox;
    private Object mBoxLock = new Object();

    public OcrRunnable(MainService context, CaptureWindow captureWindow){
        mContext = context;
        mCaptureWindow = captureWindow;
        mBox = null;

        mTessBaseAPI = new TessBaseAPI();
        String storagePath = mContext.getExternalFilesDir(null).getAbsolutePath();
        Log.e(TAG, storagePath);
        mTessBaseAPI.init(storagePath, "jpn");
        //mTessBaseAPI.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK_VERT_TEXT);
    }

    @Override
    public void run() {

        while(mRunning){

            Log.d(TAG, "THREAD STARTING NEW LOOP");

            try {

                if (mBox == null){
                    synchronized (mBoxLock){
                        Log.d(TAG, "WAITING");
                        mBoxLock.wait();
                        if (mBox == null){
                            continue;
                        }
                    }
                }

                Log.d(TAG, "THREAD STOPPED WAITING");

                long startTime = System.currentTimeMillis();
                Bitmap mBitmap = getReadyScreenshotBox(mBox);
                long screenTime = System.currentTimeMillis();

                saveBitmap(mBitmap);

                if (mBitmap == null){
                    sendToastToContext("Error getting image");
                    mBox = null;
                    continue;
                }

                mCaptureWindow.showLoadingAnimation();

                mTessBaseAPI.setImage(mBitmap);
                String hocr = mTessBaseAPI.getHOCRText(0);
                List<OcrChar> ocrChars = processOcrIterator(mTessBaseAPI.getResultIterator());
                mTessBaseAPI.clear();

                if (ocrChars.size() > 0){
                    sendOcrResultToContext(new OcrResult(mBitmap, ocrChars, screenTime - startTime, System.currentTimeMillis() - screenTime));
                }
                else{
                    sendToastToContext("No Characters Recognized.");
                }

                mCaptureWindow.stopLoadingAnimation();

                mBox = null;
            }
            catch (FileNotFoundException e){
                e.printStackTrace();
            }
            catch (OutOfMemoryError e){
                e.printStackTrace();
            }
            catch (StackOverflowError e){
                e.printStackTrace();
            }
            catch (InterruptedException e){
                e.printStackTrace();
            }
            catch (TimeoutException e) {
                e.printStackTrace();
            }
        }
    }

    public void runTess(BoxParams box){
        synchronized (mBoxLock){
            mBox = box;
            mTessBaseAPI.stop();
            mBoxLock.notify();
            Log.d(TAG, "NOTIFIED");
        }
    }

    public void cancel(){
        mTessBaseAPI.stop();
        Log.d(TAG, "CANCELED");
    }

    @Override
    public void stop(){
        synchronized (mBoxLock){
            mRunning = false;
            mTessBaseAPI.stop();
            Log.d(TAG, "THREAD STOPPED");
        }
    }

    private Bitmap getReadyScreenshotBox(BoxParams box) throws OutOfMemoryError, StackOverflowError, TimeoutException, FileNotFoundException {

        Log.d(TAG, String.format("X:%d Y:%d (%dx%d)", box.x, box.y, box.width, box.height));

        Bitmap bitmapOriginal = convertImageToBitmap(mContext.getScreenshot());
        Bitmap croppedBitmap = Bitmap.createBitmap(bitmapOriginal, box.x, box.y, box.width, box.height);
        bitmapOriginal.recycle();
        return croppedBitmap;
    }

    private Bitmap convertImageToBitmap(Image image) throws OutOfMemoryError {

        Log.d(TAG, String.format("Image Dimensions: %dx%d", image.getWidth(), image.getHeight()));

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        image.close();

        return bitmap;
    }

    private void saveBitmap(Bitmap bitmap) throws FileNotFoundException {
        String fs = String.format("%s/screenshots/screen %d.png", mContext.getExternalFilesDir(null).getAbsolutePath(), System.nanoTime());
        Log.d(TAG, fs);
        FileOutputStream fos = new FileOutputStream(fs);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
    }

    private List<OcrChar> processOcrIterator(ResultIterator iterator){

        List<OcrChar> ocrChars = new ArrayList<>();

        if (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)){
            iterator.begin();
        }
        else {
            return ocrChars;
        }

        do {
            List<Pair<String, Double>> choices = iterator.getChoicesAndConfidence(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL);
            int[] pos = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL);
            ocrChars.add(new OcrChar(choices, pos));
        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL));

        iterator.delete();

        return ocrChars;
    }

    private void sendOcrResultToContext(OcrResult result){
        Message.obtain(mContext.getHandler(), 0, result).sendToTarget();
    }

    private void sendToastToContext(String message){
        Message.obtain(mContext.getHandler(), 0, message).sendToTarget();
    }
}
