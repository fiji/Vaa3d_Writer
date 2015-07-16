
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;

import java.io.File;
import java.io.RandomAccessFile;
//  Brian Long
//  2014.02.14 Plugin for writing .v3draw files
//  based on Albert Cardona's web example and previous raw_writer.java plugins included with Vaa3d
//  for information on vaa3d see:  http://vaa3d.org

//  Deviations from Cardona example:
//                              _ Use RandomAccessFile instead of FileOutputStream to facilitate fast writing of 
//				multichannel images in .v3draw format, which is in xyzc format
//				
// Corrections from original raw_writer.java in Vaa3d codebase
//                              _got rid of Byte64Array class that piled the whole image into one variable, which
//    				made virtual stacks irrelevant 
//				_corrected error in switch-case syntax to allow correct writing of RBG files
//					
public class Vaa3d_Writer implements PlugIn {

    public void run(String arg) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (null == imp) {
            return;
        }
        SaveDialog sd = new SaveDialog("Save .v3draw...", ".v3draw", null);
        String dir = sd.getDirectory();
        if (null == dir) {
            return;                     // user canceled dialog  
        }
        dir = dir.replace('\\', '/');   // Windows safe  
        if (!dir.endsWith("/")) {
            dir += "/";
        }

        saveV3draw(imp, dir + sd.getFileName());
    }

    static public void saveV3draw(ImagePlus imp, String path) {
        String formatkey = "raw_image_stack_by_hpeng";  // for header  
        File file = new File(path);
        RandomAccessFile out = null;
        try {

            int imtype = imp.getType(); //  get image information 
            int[] dim = new int[5];
            dim = imp.getDimensions();
            if (dim[4] != 1) {
                IJ.error("Error: Currently .v3draw writing not support 5d hyperstacks.");
                return;
            }
            int[] sz = new int[4];      // new variable for clarity: vaa3d is xyzct format
            sz[0] = dim[0];
            sz[1] = dim[1];
            sz[2] = dim[3];
            sz[3] = dim[2];

            int unitSize;               // unitsize for writing, depending on  ImagePlus type
            switch (imtype) {
                case ImagePlus.COLOR_RGB:
                    unitSize = 1;
                    sz[3] = 3;
                    break;
                case ImagePlus.GRAY8:
                    unitSize = 1;
                    break;
                case ImagePlus.GRAY16:
                    unitSize = 2;
                    break;
                case ImagePlus.GRAY32:
                    unitSize = 4;
                    break;
                default:
                    unitSize = imp.getBitDepth() / 8;
            }

            out = new RandomAccessFile(file, "rw");
            out.write(formatkey.getBytes());        // write 	format key 
            out.write("B".getBytes());              //       	endianness.
            out.write(int2byte(unitSize, 2));       //       	unitSize 
            for (int d : sz) {
                out.write(int2byte(d, 4));          // and    	image dimensions into header
            }

            int w = sz[0];
            int h = sz[1];
            int nChannel = sz[3];
            int bitdepth = imp.getBitDepth();

            int layerOffset = w * h;
            long colorOffset = layerOffset * (long) sz[2] * unitSize;

            ImageStack stack = imp.getStack();

            switch (imtype) {
                case ImagePlus.COLOR_RGB:
                    byte[] r = new byte[layerOffset];
                    byte[] g = new byte[layerOffset];
                    byte[] b = new byte[layerOffset];
                    long currentRedPointer = 43;
                    long currentGreenPointer = 43 + colorOffset;
                    long currentBluePointer = 43 + 2 * colorOffset;
                    for (int layer = 1; layer <= sz[2]; layer++) {
                        ColorProcessor cp = (ColorProcessor) stack.getProcessor(layer);
                        cp.getRGB(r, g, b);

                        out.seek(currentRedPointer);
                        out.write(r);
                        currentRedPointer = out.getFilePointer();

                        out.seek(currentGreenPointer);
                        out.write(g);
                        currentGreenPointer = out.getFilePointer();

                        out.seek(currentBluePointer);
                        out.write(b);
                        currentBluePointer = out.getFilePointer();

                    }
                    r = null;
                    g = null;
                    b = null;
                    break;

                case ImagePlus.GRAY8:
                    byte[] imtmp = new byte[layerOffset];
                    long[] currentPointers = new long[sz[3]];

                    for (int cNumber = 0; cNumber < sz[3]; cNumber++) {
                        currentPointers[cNumber] = 43 + (long) (cNumber) * colorOffset;
                    }
                    for (int layer = 1; layer <= sz[2]; layer++) {
                        for (int channelIndex = 0; channelIndex < sz[3]; channelIndex++) {
                            out.seek(currentPointers[channelIndex]);
                            imtmp = (byte[]) stack.getPixels((layer - 1) * sz[3] + channelIndex + 1);
                            out.write(imtmp);
                            currentPointers[channelIndex] = out.getFilePointer();
                        }
                    }
                    imtmp = null;
                    break;

                case ImagePlus.GRAY16:
                    byte[] im16asbytes = new byte[2 * layerOffset];
                    long[] currentPointers16 = new long[sz[3]];

                    for (int cNumber = 0; cNumber < sz[3]; cNumber++) {
                        currentPointers16[cNumber] = 43 + (long) (cNumber) * colorOffset;
                    }

                    for (int layer = 1; layer <= sz[2]; layer++) {
                        for (int channelIndex = 0; channelIndex < sz[3]; channelIndex++) {
                            out.seek(currentPointers16[channelIndex]);
                            im16asbytes = ShortToByte_Twiddle_Method((short[]) stack.getPixels((layer - 1) * sz[3] + channelIndex + 1));
                            out.write(im16asbytes);
                            currentPointers16[channelIndex] = out.getFilePointer();
                        }
                    }
                    im16asbytes = null;
                    break;
                case ImagePlus.GRAY32:
                    long[] im32 = new long[layerOffset];
                    byte[] im32asbytes = new byte[4 * layerOffset];
                    long[] currentPointers32 = new long[sz[3]];

                    for (int cNumber = 0; cNumber < sz[3]; cNumber++) {
                        currentPointers32[cNumber] = 43 + cNumber * colorOffset;
                    }

                    for (int layer = 1; layer <= sz[2]; layer++) {
                        for (int channelIndex = 0; channelIndex < sz[3]; channelIndex++) {
                            out.seek(currentPointers32[channelIndex]);
                            im32asbytes = FloatToByte_Twiddle_Method((float[]) stack.getPixels((layer - 1) * sz[3] + channelIndex + 1));
                            out.write(im32asbytes);
                            currentPointers32[channelIndex] = out.getFilePointer();
                        }
                    }
                    im32asbytes = null;
                    break;
                default:
                    IJ.error("Image type not supported by this plugin");
                    return;
            }

            out.close();
            IJ.showStatus("Saved " + path);

        } catch (Exception e) {
            IJ.error("Error:" + e.toString());
            return;
        } finally {
        }

    }

    static byte[] int2byte(int num, int len)                // original function still used for header text, there's probably a better way to do this...
    {
        byte[] by = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            by[i] = (byte) (num & 0xFF);
            num = num >> 8;
        }
        return (by);
    }

    static byte[] ShortToByte_Twiddle_Method(short[] input) //brl borrowed from stackexchange, modified byte order for image data
    {
        int short_index, byte_index;
        int iterations = input.length;

        byte[] buffer = new byte[input.length * 2];

        short_index = byte_index = 0;

        for (/*NOP*/; short_index != iterations; /*NOP*/) {
            buffer[byte_index] = (byte) ((input[short_index] & 0xFF00) >> 8);
            buffer[byte_index + 1] = (byte) (input[short_index] & 0x00FF);

            ++short_index;
            byte_index += 2;
        }

        return buffer;
    }

    static byte[] FloatToByte_Twiddle_Method(float[] input) //
    {
        int short_index, byte_index;
        int iterations = input.length;

        byte[] buffer = new byte[input.length * 4];

        short_index = byte_index = 0;
        int t = 0;
        for (/*NOP*/; short_index != iterations; /*NOP*/) {
            t = Float.floatToIntBits(input[short_index]);
            buffer[byte_index + 3] = (byte) (t & 0xFF);
            t = t >> 8;
            buffer[byte_index + 2] = (byte) (t & 0xFF);
            t = t >> 8;
            buffer[byte_index + 1] = (byte) (t & 0xFF);
            t = t >> 8;
            buffer[byte_index + 0] = (byte) (t & 0xFF);
            t = t >> 8;
            ++short_index;
            byte_index += 4;
        }

        return buffer;
    }

}
