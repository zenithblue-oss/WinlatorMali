package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.GraphicsContext;
import com.winlator.cmod.xserver.SHMSegmentManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadGraphicsContext;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadSHMSegment;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MITSHMExtension implements Extension {
    public static final byte MAJOR_OPCODE = -101;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
        private static final byte ATTACH_FD = 6;
    }

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 64;
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)2); // 1.2: AttachFd (memfd, no SysV shm needed); PanVK's sw WSI presents through it
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)0);
            outputStream.writePad(15); // replies are 32 bytes; at 17, xcb (Mesa X11 sw WSI) waited forever for the rest
        }
    }

    private static void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        client.xServer.getSHMSegmentManager().attach(client, xid, shmid);
    }

    private static void attachFd(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        inputStream.skip(4); // read_only (always mapped read-only) + pad
        int fd = inputStream.getAncillaryFd();
        SHMSegmentManager manager = client.xServer.getSHMSegmentManager();
        if (fd < 0) throw new BadSHMSegment(xid);
        if (manager == null) {
            XConnectorEpoll.closeFd(fd);
            throw new BadSHMSegment(xid);
        }
        manager.attachFd(client, xid, fd); // throws BadAlloc over the per-client cap
    }

    private static void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        SHMSegmentManager manager = client.xServer.getSHMSegmentManager();
        int xid = inputStream.readInt();
        if (!manager.isOwner(client, xid)) return; // never detach another client's segment
        manager.detach(xid);
    }

    private static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        long offset = inputStream.readInt() & 0xFFFFFFFFL;

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = client.xServer.getSHMSegmentManager().getData(shmseg);
        if (data == null || !client.xServer.getSHMSegmentManager().isOwner(client, shmseg)) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        // 64-bit math: offset is a client-controlled uint32; the image must lie fully inside the segment.
        long tw = totalWidth & 0xFFFFL, th = totalHeight & 0xFFFFL;
        long sx = srcX & 0xFFFFL, sy = srcY & 0xFFFFL, sw = srcWidth & 0xFFFFL, sh = srcHeight & 0xFFFFL;
        if (sx + sw > tw || sy + sh > th) throw new BadValue(srcX);
        long imageSize = depth == 1 ? ((tw + 31) / 32 * 4) * th : tw * th * 4;
        if (offset + imageSize > data.capacity()) throw new BadValue((int)offset);
        if (offset != 0) {
            data = data.duplicate();
            data.position((int)offset);
            data = data.slice().order(data.order());
        }

        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, data, totalWidth, totalHeight);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.ATTACH_FD :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attachFd(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
