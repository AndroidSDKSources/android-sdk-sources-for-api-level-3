/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.net.http.EventHandler;
import android.net.http.Headers;
import android.os.Handler;
import android.os.Message;
import android.util.Config;

import java.io.IOException;
import java.io.InputStream;


/**
 * This abstract class is used for all content loaders that rely on streaming
 * content into the rendering engine loading framework.
 *
 * The class implements a state machine to load the content into the frame in
 * a similar manor to the way content arrives from the network. The class uses
 * messages to move from one state to the next, which enables async. loading of
 * the streamed content.
 *
 * Classes that inherit from this class must implement two methods, the first
 * method is used to setup the InputStream and notify the loading framework if
 * it can load it's content. The other method allows the derived class to add
 * additional HTTP headers to the response.
 *
 * By default, content loaded with a StreamLoader is marked with a HTTP header
 * that indicates the content should not be cached.
 *
 */
abstract class StreamLoader extends Handler {

    public static final String NO_STORE       = "no-store";

    private static final int MSG_STATUS = 100;  // Send status to loader
    private static final int MSG_HEADERS = 101; // Send headers to loader
    private static final int MSG_DATA = 102;  // Send data to loader
    private static final int MSG_END = 103;  // Send endData to loader

    protected LoadListener mHandler; // loader class
    protected InputStream mDataStream; // stream to read data from
    protected long mContentLength; // content length of data
    private byte [] mData; // buffer to pass data to loader with.

    /**
     * Constructor. Although this class calls the LoadListener, it only calls
     * the EventHandler Interface methods. LoadListener concrete class is used
     * to avoid the penality of calling an interface.
     *
     * @param loadlistener The LoadListener to call with the data.
     */
    StreamLoader(LoadListener loadlistener) {
        mHandler = loadlistener;
    }

    /**
     * This method is called when the derived class should setup mDataStream,
     * and call mHandler.status() to indicate that the load can occur. If it
     * fails to setup, it should still call status() with the error code.
     *
     * @return true if stream was successfully setup
     */
    protected abstract boolean setupStreamAndSendStatus();

    /**
     * This method is called when the headers are about to be sent to the
     * load framework. The derived class has the opportunity to add addition
     * headers.
     *
     * @param headers Map of HTTP headers that will be sent to the loader.
     */
    abstract protected void buildHeaders(Headers headers);


    /**
     * Calling this method starts the load of the content for this StreamLoader.
     * This method simply posts a message to send the status and returns
     * immediately.
     */
    public void load() {
        if (!mHandler.isSynchronous()) {
            sendMessage(obtainMessage(MSG_STATUS));
        } else {
            // Load the stream synchronously.
            if (setupStreamAndSendStatus()) {
                // We were able to open the stream, create the array
                // to pass data to the loader
                mData = new byte[8192];
                sendHeaders();
                while (!sendData());
                closeStreamAndSendEndData();
                mHandler.loadSynchronousMessages();
            }
        }
    }

    /* (non-Javadoc)
     * @see android.os.Handler#handleMessage(android.os.Message)
     */
    public void handleMessage(Message msg) {
        if (Config.DEBUG && mHandler.isSynchronous()) {
            throw new AssertionError();
        }
        switch(msg.what) {
            case MSG_STATUS:
                if (setupStreamAndSendStatus()) {
                    // We were able to open the stream, create the array
                    // to pass data to the loader
                    mData = new byte[8192];
                    sendMessage(obtainMessage(MSG_HEADERS));
                }
                break;
            case MSG_HEADERS:
                sendHeaders();
                sendMessage(obtainMessage(MSG_DATA));
                break;
            case MSG_DATA:
                if (sendData()) {
                    sendMessage(obtainMessage(MSG_END));
                } else {
                    sendMessage(obtainMessage(MSG_DATA));
                }
                break;
            case MSG_END:
                closeStreamAndSendEndData();
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    /**
     * Construct the headers and pass them to the EventHandler.
     */
    private void sendHeaders() {
        Headers headers = new Headers();
        if (mContentLength > 0) {
            headers.setContentLength(mContentLength);
        }
        headers.setCacheControl(NO_STORE);
        buildHeaders(headers);
        mHandler.headers(headers);
    }

    /**
     * Read data from the stream and pass it to the EventHandler.
     * If an error occurs reading the stream, then an error is sent to the
     * EventHandler, and moves onto the next state - end of data.
     * @return True if all the data has been read. False if sendData should be
     *         called again.
     */
    private boolean sendData() {
        if (mDataStream != null) {
            try {
                int amount = mDataStream.read(mData);
                if (amount > 0) {
                    mHandler.data(mData, amount);
                    return false;
                }
            } catch (IOException ex) {
                mHandler.error(EventHandler.FILE_ERROR,
                               ex.getMessage());
            }
        }
        return true;
    }

    /**
     * Close the stream and inform the EventHandler that load is complete.
     */
    private void closeStreamAndSendEndData() {
        if (mDataStream != null) {
            try {
                mDataStream.close();
            } catch (IOException ex) {
                // ignore.
            }
        }
        mHandler.endData();
    }

}
