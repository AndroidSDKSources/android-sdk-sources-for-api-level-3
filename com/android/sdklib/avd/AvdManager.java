/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.sdklib.avd;

import com.android.prefs.AndroidLocation;
import com.android.prefs.AndroidLocation.AndroidLocationException;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.ISdkLog;
import com.android.sdklib.SdkConstants;
import com.android.sdklib.SdkManager;
import com.android.sdklib.avd.AvdManager.AvdInfo.AvdStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android Virtual Device Manager to manage AVDs.
 */
public final class AvdManager {
    
    /**
     * Exception thrown when something is wrong with a target path.
     */
    private final static class InvalidTargetPathException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidTargetPathException(String message) {
            super(message);
        }
    }
    
    public static final String AVD_FOLDER_EXTENSION = ".avd";

    public final static String AVD_INFO_PATH = "path";
    public final static String AVD_INFO_TARGET = "target";
    
    /**
     * AVD/config.ini key name representing the SDK-relative path of the skin folder, if any,
     * or a 320x480 like constant for a numeric skin size.
     * 
     * @see #NUMERIC_SKIN_SIZE
     */
    public final static String AVD_INI_SKIN_PATH = "skin.path";
    /**
     * AVD/config.ini key name representing an UI name for the skin.
     * This config key is ignored by the emulator. It is only used by the SDK manager or
     * tools to give a friendlier name to the skin.
     * If missing, use the {@link #AVD_INI_SKIN_PATH} key instead.
     */
    public final static String AVD_INI_SKIN_NAME = "skin.name";
    /**
     * AVD/config.ini key name representing the path to the sdcard file.
     * If missing, the default name "sdcard.img" will be used for the sdcard, if there's such
     * a file.
     * 
     * @see #SDCARD_IMG
     */
    public final static String AVD_INI_SDCARD_PATH = "sdcard.path";
    /**
     * AVD/config.ini key name representing the size of the SD card.
     * This property is for UI purposes only. It is not used by the emulator.
     * 
     * @see #SDCARD_SIZE_PATTERN
     */
    public final static String AVD_INI_SDCARD_SIZE = "sdcard.size";
    /**
     * AVD/config.ini key name representing the first path where the emulator looks
     * for system images. Typically this is the path to the add-on system image or
     * the path to the platform system image if there's no add-on.
     * <p/>
     * The emulator looks at {@link #AVD_INI_IMAGES_1} before {@link #AVD_INI_IMAGES_2}.
     */
    public final static String AVD_INI_IMAGES_1 = "image.sysdir.1";
    /**
     * AVD/config.ini key name representing the second path where the emulator looks
     * for system images. Typically this is the path to the platform system image.
     * 
     * @see #AVD_INI_IMAGES_1
     */
    public final static String AVD_INI_IMAGES_2 = "image.sysdir.2";

    /**
     * Pattern to match pixel-sized skin "names", e.g. "320x480".
     */
    public final static Pattern NUMERIC_SKIN_SIZE = Pattern.compile("[0-9]{2,}x[0-9]{2,}");

    private final static String USERDATA_IMG = "userdata.img";
    private final static String CONFIG_INI = "config.ini";
    private final static String SDCARD_IMG = "sdcard.img";

    private final static String INI_EXTENSION = ".ini";
    private final static Pattern INI_NAME_PATTERN = Pattern.compile("(.+)\\" + INI_EXTENSION + "$",
            Pattern.CASE_INSENSITIVE);

    private final static Pattern IMAGE_NAME_PATTERN = Pattern.compile("(.+)\\.img$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Pattern for matching SD Card sizes, e.g. "4K" or "16M".
     */
    private final static Pattern SDCARD_SIZE_PATTERN = Pattern.compile("\\d+[MK]?");

    /** An immutable structure describing an Android Virtual Device. */
    public static final class AvdInfo {
        
        /**
         * Status for an {@link AvdInfo}. Indicates whether or not this AVD is valid.
         */
        public static enum AvdStatus {
            /** No error */
            OK,
            /** Missing 'path' property in the ini file */
            ERROR_PATH,
            /** Missing config.ini file in the AVD data folder */
            ERROR_CONFIG,
            /** Missing 'target' property in the ini file */
            ERROR_TARGET_HASH,
            /** Target was not resolved from its hash */
            ERROR_TARGET,
            /** Unable to parse config.ini */
            ERROR_PROPERTIES,
            /** System Image folder in config.ini doesn't exist */
            ERROR_IMAGE_DIR;
        }
        
        private final String mName;
        private final String mPath;
        private final String mTargetHash;
        private final IAndroidTarget mTarget;
        private final Map<String, String> mProperties;
        private final AvdStatus mStatus;

        /**
         * Creates a new valid AVD info. Values are immutable. 
         * <p/>
         * Such an AVD is available and can be used.
         * The error string is set to null.
         * 
         * @param name The name of the AVD (for display or reference)
         * @param path The path to the config.ini file
         * @param targetHash the target hash
         * @param target The target. Can be null, if the target was not resolved.
         * @param properties The property map. Cannot be null.
         */
        public AvdInfo(String name, String path, String targetHash, IAndroidTarget target,
                Map<String, String> properties) {
            this(name, path, targetHash, target, properties, AvdStatus.OK);
        }

        /**
         * Creates a new <em>invalid</em> AVD info. Values are immutable. 
         * <p/>
         * Such an AVD is not complete and cannot be used.
         * The error string must be non-null.
         * 
         * @param name The name of the AVD (for display or reference)
         * @param path The path to the config.ini file
         * @param targetHash the target hash
         * @param target The target. Can be null, if the target was not resolved.
         * @param properties The property map. Can be null.
         * @param error The error describing why this AVD is invalid. Cannot be null.
         */
        public AvdInfo(String name, String path, String targetHash, IAndroidTarget target,
                Map<String, String> properties, AvdStatus status) {
            mName = name;
            mPath = path;
            mTargetHash = targetHash;
            mTarget = target;
            mProperties = Collections.unmodifiableMap(properties);
            mStatus = status;
        }

        /** Returns the name of the AVD. */
        public String getName() {
            return mName;
        }

        /** Returns the path of the AVD data directory. */
        public String getPath() {
            return mPath;
        }
        
        /**
         * Returns the target hash string.
         */
        public String getTargetHash() {
            return mTargetHash;
        }

        /** Returns the target of the AVD, or <code>null</code> if it has not been resolved */
        public IAndroidTarget getTarget() {
            return mTarget;
        }

        /** Returns the {@link AvdStatus} of the receiver. */
        public AvdStatus getStatus() {
            return mStatus;
        }

        /** 
         * Helper method that returns the .ini {@link File} for a given AVD name. 
         * @throws AndroidLocationException if there's a problem getting android root directory.
         */
        public static File getIniFile(String name) throws AndroidLocationException {
            String avdRoot;
            avdRoot = AndroidLocation.getFolder() + AndroidLocation.FOLDER_AVD;
            return new File(avdRoot, name + INI_EXTENSION);
        }

        /** 
         * Returns the .ini {@link File} for this AVD. 
         * @throws AndroidLocationException if there's a problem getting android root directory.
         */
        public File getIniFile() throws AndroidLocationException {
            return getIniFile(mName);
        }
        
        /** 
         * Helper method that returns the Config {@link File} for a given AVD name. 
         */
        public static File getConfigFile(String path) {
            return new File(path, CONFIG_INI);
        }
        
        /** 
         * Returns the Config {@link File} for this AVD. 
         */
        public File getConfigFile() {
            return getConfigFile(mPath);
        }

        /**
         * Returns a map of properties for the AVD.
         */
        public Map<String, String> getProperties() {
            return mProperties;
        }
        
        /**
         * Returns the error message for the AVD or <code>null</code> if {@link #getStatus()}
         * returns {@link AvdStatus#OK}
         */
        public String getErrorMessage() {
            try {
                switch (mStatus) {
                    case ERROR_PATH:
                        return String.format("Missing AVD 'path' property in %1$s", getIniFile());
                    case ERROR_CONFIG:
                        return String.format("Missing config.ini file in %1$s", mPath);
                    case ERROR_TARGET_HASH:
                        return String.format("Missing 'target' property in %1$s", getIniFile());
                    case ERROR_TARGET:
                        return String.format("Unknown target '%1$s' in %2$s",
                                mTargetHash, getIniFile());
                    case ERROR_PROPERTIES:
                        return String.format("Failed to parse properties from %1$s",
                                getConfigFile());
                    case ERROR_IMAGE_DIR:
                        return String.format(
                                "Invalid value in image.sysdir. Run 'android update avd -n %1$s'",
                                mName);
                    case OK:
                        assert false;
                        return null;
                }
            } catch (AndroidLocationException e) {
                return "Unable to get HOME folder.";
            }
            
            return null;
        }
    }

    private final ArrayList<AvdInfo> mAllAvdList = new ArrayList<AvdInfo>();
    private AvdInfo[] mValidAvdList;
    private AvdInfo[] mBrokenAvdList;
    private ISdkLog mSdkLog;
    private final SdkManager mSdk;

    public AvdManager(SdkManager sdk, ISdkLog sdkLog) throws AndroidLocationException {
        mSdk = sdk;
        mSdkLog = sdkLog;
        buildAvdList(mAllAvdList);
    }

    /**
     * Returns all the existing AVDs.
     * @return a newly allocated array containing all the AVDs.
     */
    public AvdInfo[] getAllAvds() {
        synchronized (mAllAvdList) {
            return mAllAvdList.toArray(new AvdInfo[mAllAvdList.size()]);
        }
    }

    /**
     * Returns all the valid AVDs.
     * @return a newly allocated array containing all valid the AVDs.
     */
    public AvdInfo[] getValidAvds() {
        synchronized (mAllAvdList) {
            if (mValidAvdList == null) {
                ArrayList<AvdInfo> list = new ArrayList<AvdInfo>();
                for (AvdInfo avd : mAllAvdList) {
                    if (avd.getStatus() == AvdStatus.OK) {
                        list.add(avd);
                    }
                }
                
                mValidAvdList = list.toArray(new AvdInfo[list.size()]);
            }
            return mValidAvdList;
        }
    }

    /**
     * Returns all the broken AVDs.
     * @return a newly allocated array containing all the broken AVDs.
     */
    public AvdInfo[] getBrokenAvds() {
        synchronized (mAllAvdList) {
            if (mBrokenAvdList == null) {
                ArrayList<AvdInfo> list = new ArrayList<AvdInfo>();
                for (AvdInfo avd : mAllAvdList) {
                    if (avd.getStatus() != AvdStatus.OK) {
                        list.add(avd);
                    }
                }
                mBrokenAvdList = list.toArray(new AvdInfo[list.size()]);
            }
            return mBrokenAvdList;
        }
    }

    /**
     * Returns the {@link AvdInfo} matching the given <var>name</var>.
     * @param name the name of the AVD to return
     * @param validAvdOnly if <code>true</code>, only look through the list of valid AVDs.
     * @return the matching AvdInfo or <code>null</code> if none were found.
     */
    public AvdInfo getAvd(String name, boolean validAvdOnly) {
        if (validAvdOnly) {
            for (AvdInfo info : getValidAvds()) {
                if (info.getName().equals(name)) {
                    return info;
                }
            }
        } else {
            synchronized (mAllAvdList) {
                for (AvdInfo info : getValidAvds()) {
                    if (info.getName().equals(name)) {
                        return info;
                    }
                }
            }
        }

        return null;
    }
    
    /**
     * Reloads the AVD list.
     * @throws AndroidLocationException if there was an error finding the location of the
     * AVD folder.
     */
    public void reloadAvds() throws AndroidLocationException {
        // build the list in a temp list first, in case the method throws an exception.
        // It's better than deleting the whole list before reading the new one.
        ArrayList<AvdInfo> allList = new ArrayList<AvdInfo>();
        buildAvdList(allList);

        synchronized (mAllAvdList) {
            mAllAvdList.clear();
            mAllAvdList.addAll(allList);
            mValidAvdList = mBrokenAvdList = null;
        }
    }

    /**
     * Creates a new AVD. It is expected that there is no existing AVD with this name already.
     * @param avdFolder the data folder for the AVD. It will be created as needed.
     * @param name the name of the AVD
     * @param target the target of the AVD
     * @param skinName the name of the skin. Can be null. Must have been verified by caller.
     * @param sdcard the parameter value for the sdCard. Can be null. This is either a path to
     * an existing sdcard image or a sdcard size (\d+, \d+K, \dM).
     * @param hardwareConfig the hardware setup for the AVD
     * @param removePrevious If true remove any previous files.
     */
    public AvdInfo createAvd(File avdFolder, String name, IAndroidTarget target,
            String skinName, String sdcard, Map<String,String> hardwareConfig,
            boolean removePrevious) {
        
        File iniFile = null;
        boolean needCleanup = false;
        try {
            if (avdFolder.exists()) {
                if (removePrevious) {
                    // AVD already exists and removePrevious is set, try to remove the
                    // directory's content first (but not the directory itself).
                    recursiveDelete(avdFolder);
                } else {
                    // AVD shouldn't already exist if removePrevious is false.
                    if (mSdkLog != null) {
                        mSdkLog.error(null,
                                "Folder %1$s is in the way. Use --force if you want to overwrite.",
                                avdFolder.getAbsolutePath());
                    }
                    return null;
                }
            } else {
                // create the AVD folder.
                avdFolder.mkdir();
            }

            // actually write the ini file
            iniFile = createAvdIniFile(name, avdFolder, target);

            // writes the userdata.img in it.
            String imagePath = target.getPath(IAndroidTarget.IMAGES);
            File userdataSrc = new File(imagePath, USERDATA_IMG);
            
            if (userdataSrc.exists() == false && target.isPlatform() == false) {
                imagePath = target.getParent().getPath(IAndroidTarget.IMAGES);
                userdataSrc = new File(imagePath, USERDATA_IMG);
            }
            
            if (userdataSrc.exists() == false) {
                mSdkLog.error(null, "Unable to find a '%1$s' file to copy into the AVD folder.",
                        USERDATA_IMG);
                needCleanup = true;
                return null;
            }
            
            FileInputStream fis = new FileInputStream(userdataSrc);
            
            File userdataDest = new File(avdFolder, USERDATA_IMG);
            FileOutputStream fos = new FileOutputStream(userdataDest);
            
            byte[] buffer = new byte[4096];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, count);
            }
            
            fos.close();
            fis.close();
            
            // Config file.
            HashMap<String, String> values = new HashMap<String, String>();

            if (setImagePathProperties(target, values) == false) {
                needCleanup = true;
                return null;
            }
            
            // Now the skin.
            if (skinName == null) {
                skinName = target.getDefaultSkin();
            }

            if (NUMERIC_SKIN_SIZE.matcher(skinName).matches()) {
                // Skin name is an actual screen resolution.
                // Set skin.name for display purposes in the AVD manager and
                // set skin.path for use by the emulator.
                values.put(AVD_INI_SKIN_NAME, skinName);
                values.put(AVD_INI_SKIN_PATH, skinName);
            } else {
                // get the path of the skin (relative to the SDK)
                // assume skin name is valid
                String skinPath = getSkinRelativePath(skinName, target);
                if (skinPath == null) {
                    needCleanup = true;
                    return null;
                }

                values.put(AVD_INI_SKIN_PATH, skinPath);
                values.put(AVD_INI_SKIN_NAME, skinName);
            }

            if (sdcard != null) {
                File sdcardFile = new File(sdcard);
                if (sdcardFile.isFile()) {
                    // sdcard value is an external sdcard, so we put its path into the config.ini
                    values.put(AVD_INI_SDCARD_PATH, sdcard);
                } else {
                    // Sdcard is possibly a size. In that case we create a file called 'sdcard.img'
                    // in the AVD folder, and do not put any value in config.ini.
                    
                    // First, check that it matches the pattern for sdcard size
                    Matcher m = SDCARD_SIZE_PATTERN.matcher(sdcard);
                    if (m.matches()) {
                        // create the sdcard.
                        sdcardFile = new File(avdFolder, SDCARD_IMG);
                        String path = sdcardFile.getAbsolutePath();
                        
                        // execute mksdcard with the proper parameters.
                        File toolsFolder = new File(mSdk.getLocation(), SdkConstants.FD_TOOLS);
                        File mkSdCard = new File(toolsFolder, SdkConstants.mkSdCardCmdName());
                        
                        if (mkSdCard.isFile() == false) {
                            mSdkLog.error(null, "'%1$s' is missing from the SDK tools folder.",
                                    mkSdCard.getName());
                            needCleanup = true;
                            return null;
                        }
                        
                        if (createSdCard(mkSdCard.getAbsolutePath(), sdcard, path) == false) {
                            needCleanup = true;
                            return null; // mksdcard output has already been displayed, no need to
                                         // output anything else.
                        }
                        
                        // add a property containing the size of the sdcard for display purpose
                        // only when the dev does 'android list avd'
                        values.put(AVD_INI_SDCARD_SIZE, sdcard);
                    } else {
                        mSdkLog.error(null,
                                "'%1$s' is not recognized as a valid sdcard value.\n" +
                                "Value should be:\n" +
                                "1. path to an sdcard.\n" +
                                "2. size of the sdcard to create: <size>[K|M]",
                                sdcard);
                        needCleanup = true;
                        return null;
                    }
                }
            }

            if (hardwareConfig != null) {
                values.putAll(hardwareConfig);
            }

            File configIniFile = new File(avdFolder, CONFIG_INI);
            writeIniFile(configIniFile, values);
            
            if (mSdkLog != null) {
                if (target.isPlatform()) {
                    mSdkLog.printf("Created AVD '%1$s' based on %2$s\n", name, target.getName());
                } else {
                    mSdkLog.printf("Created AVD '%1$s' based on %2$s (%3$s)\n", name, target.getName(),
                               target.getVendor());
                }
            }
            
            // create the AvdInfo object, and add it to the list
            AvdInfo avdInfo = new AvdInfo(name, avdFolder.getAbsolutePath(), target.hashString(),
                    target, values);
            
            synchronized (mAllAvdList) {
                mAllAvdList.add(avdInfo);
                mValidAvdList = mBrokenAvdList = null;
            }
            
            return avdInfo;
        } catch (AndroidLocationException e) {
            if (mSdkLog != null) {
                mSdkLog.error(e, null);
            }
        } catch (IOException e) {
            if (mSdkLog != null) {
                mSdkLog.error(e, null);
            }
        } finally {
            if (needCleanup) {
                if (iniFile != null && iniFile.exists()) {
                    iniFile.delete();
                }
                
                recursiveDelete(avdFolder);
                avdFolder.delete();
            }
        }
        
        return null;
    }

    /**
     * Returns the path to the target images folder as a relative path to the SDK, if the folder
     * is not empty. If the image folder is empty or does not exist, <code>null</code> is returned.
     * @throws InvalidTargetPathException if the target image folder is not in the current SDK.
     */
    private String getImageRelativePath(IAndroidTarget target)
            throws InvalidTargetPathException {
        String imageFullPath = target.getPath(IAndroidTarget.IMAGES);

        // make this path relative to the SDK location
        String sdkLocation = mSdk.getLocation();
        if (imageFullPath.startsWith(sdkLocation) == false) {
            // this really really should not happen.
            assert false;
            throw new InvalidTargetPathException("Target location is not inside the SDK.");
        }
        
        File folder = new File(imageFullPath);
        if (folder.isDirectory()) {
            String[] list = folder.list(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return IMAGE_NAME_PATTERN.matcher(name).matches();
                }
            });

            if (list.length > 0) {
                imageFullPath = imageFullPath.substring(sdkLocation.length());
                if (imageFullPath.charAt(0) == File.separatorChar) {
                    imageFullPath = imageFullPath.substring(1);
                }
        
                return imageFullPath;
            }
        }
        
        return null;
    }
    
    /**
     * Returns the path to the skin, as a relative path to the SDK.
     */
    private String getSkinRelativePath(String skinName, IAndroidTarget target) {
        // first look to see if the skin is in the target
        
        String path = target.getPath(IAndroidTarget.SKINS);
        File skin = new File(path, skinName);
        
        if (skin.exists() == false && target.isPlatform() == false) {
            target = target.getParent();

            path = target.getPath(IAndroidTarget.SKINS);
            skin = new File(path, skinName);
        }
        
        // skin really does not exist!
        if (skin.exists() == false) {
            mSdkLog.error(null, "Skin '%1$s' does not exist.", skinName);
            return null;
        }
        
        // get the skin path
        path = skin.getAbsolutePath();

        // make this path relative to the SDK location
        String sdkLocation = mSdk.getLocation();
        if (path.startsWith(sdkLocation) == false) {
            // this really really should not happen.
            mSdkLog.error(null, "Target location is not inside the SDK.");
            assert false;
            return null;
        }

        path = path.substring(sdkLocation.length());
        if (path.charAt(0) == File.separatorChar) {
            path = path.substring(1);
        }
        return path;
    }

    /**
     * Creates the ini file for an AVD.
     * 
     * @param name of the AVD.
     * @param avdFolder path for the data folder of the AVD.
     * @param target of the AVD.
     * @throws AndroidLocationException if there's a problem getting android root directory.
     * @throws IOException if {@link File#getAbsolutePath()} fails.
     */
    private File createAvdIniFile(String name, File avdFolder, IAndroidTarget target)
            throws AndroidLocationException, IOException {
        HashMap<String, String> values = new HashMap<String, String>();
        File iniFile = AvdInfo.getIniFile(name);
        values.put(AVD_INFO_PATH, avdFolder.getAbsolutePath());
        values.put(AVD_INFO_TARGET, target.hashString());
        writeIniFile(iniFile, values);

        return iniFile;
    }
    
    /**
     * Creates the ini file for an AVD.
     * 
     * @param info of the AVD.
     * @throws AndroidLocationException if there's a problem getting android root directory.
     * @throws IOException if {@link File#getAbsolutePath()} fails.
     */
    private File createAvdIniFile(AvdInfo info) throws AndroidLocationException, IOException {
        return createAvdIniFile(info.getName(), new File(info.getPath()), info.getTarget());
    }

    /**
     * Actually deletes the files of an existing AVD.
     * <p/>
     * This also remove it from the manager's list, The caller does not need to
     * call {@link #removeAvd(AvdInfo)} afterwards.
     * <p/>
     * This method is designed to somehow work with an unavailable AVD, that is an AVD that
     * could not be loaded due to some error. That means this method still tries to remove
     * the AVD ini file or its folder if it can be found. An error will be output if any of
     * these operations fail.
     * 
     * @param avdInfo the information on the AVD to delete
     */
    public void deleteAvd(AvdInfo avdInfo, ISdkLog log) {
        try {
            boolean error = false;
            
            File f = avdInfo.getIniFile();
            if (f != null && f.exists()) {
                log.warning("Deleting file %1$s", f.getCanonicalPath());
                if (!f.delete()) {
                    log.error(null, "Failed to delete %1$s", f.getCanonicalPath());
                    error = true;
                }
            }

            String path = avdInfo.getPath();
            if (path != null) {
                f = new File(path);
                if (f.exists()) {
                    log.warning("Deleting folder %1$s", f.getCanonicalPath());
                    recursiveDelete(f);
                    if (!f.delete()) {
                        log.error(null, "Failed to delete %1$s", f.getCanonicalPath());
                        error = true;
                    }
                }
            }

            removeAvd(avdInfo);

            if (error) {
                log.printf("AVD '%1$s' deleted with errors. See warnings above.\n",
                        avdInfo.getName());
            } else {
                log.printf("AVD '%1$s' deleted.\n", avdInfo.getName());
            }

        } catch (AndroidLocationException e) {
            log.error(e, null);
        } catch (IOException e) {
            log.error(e, null);
        }
    }
    
    /**
     * Moves and/or rename an existing AVD and its files.
     * This also change it in the manager's list.
     * <p/>
     * The caller should make sure the name or path given are valid, do not exist and are
     * actually different than current values.
     * 
     * @param avdInfo the information on the AVD to move.
     * @param newName the new name of the AVD if non null.
     * @param paramFolderPath the new data folder if non null.
     * @return True if the move succeeded or there was nothing to do.
     *         If false, this method will have had already output error in the log. 
     */
    public boolean moveAvd(AvdInfo avdInfo, String newName, String paramFolderPath, ISdkLog log) {
        
        try {
            if (paramFolderPath != null) {
                File f = new File(avdInfo.getPath());
                log.warning("Moving '%1$s' to '%2$s'.", avdInfo.getPath(), paramFolderPath);
                if (!f.renameTo(new File(paramFolderPath))) {
                    log.error(null, "Failed to move '%1$s' to '%2$s'.",
                            avdInfo.getPath(), paramFolderPath);
                    return false;
                }
    
                // update AVD info
                AvdInfo info = new AvdInfo(avdInfo.getName(), paramFolderPath,
                        avdInfo.getTargetHash(), avdInfo.getTarget(), avdInfo.getProperties());
                replaceAvd(avdInfo, info);

                // update the ini file
                createAvdIniFile(avdInfo);
            }

            if (newName != null) {
                File oldIniFile = avdInfo.getIniFile();
                File newIniFile = AvdInfo.getIniFile(newName);
                
                log.warning("Moving '%1$s' to '%2$s'.", oldIniFile.getPath(), newIniFile.getPath());
                if (!oldIniFile.renameTo(newIniFile)) {
                    log.error(null, "Failed to move '%1$s' to '%2$s'.", 
                            oldIniFile.getPath(), newIniFile.getPath());
                    return false;
                }

                // update AVD info
                AvdInfo info = new AvdInfo(newName, avdInfo.getPath(),
                        avdInfo.getTargetHash(), avdInfo.getTarget(), avdInfo.getProperties());
                replaceAvd(avdInfo, info);
            }

            log.printf("AVD '%1$s' moved.\n", avdInfo.getName());

        } catch (AndroidLocationException e) {
            log.error(e, null);
        } catch (IOException e) {
            log.error(e, null);
        }

        // nothing to do or succeeded
        return true;
    }

    /**
     * Helper method to recursively delete a folder's content (but not the folder itself).
     * 
     * @throws SecurityException like {@link File#delete()} does if file/folder is not writable.
     */
    public void recursiveDelete(File folder) {
        for (File f : folder.listFiles()) {
            if (f.isDirectory()) {
                recursiveDelete(folder);
            }
            f.delete();
        }
    }

    /**
     * Returns a list of files that are potential AVD ini files.
     * <p/>
     * This lists the $HOME/.android/avd/<name>.ini files.
     * Such files are properties file than then indicate where the AVD folder is located.
     * 
     * @return A new {@link File} array or null. The array might be empty.
     * @throws AndroidLocationException if there's a problem getting android root directory.
     */
    private File[] buildAvdFilesList() throws AndroidLocationException {
        // get the Android prefs location.
        String avdRoot = AndroidLocation.getFolder() + AndroidLocation.FOLDER_AVD;

        // ensure folder validity.
        File folder = new File(avdRoot);
        if (folder.isFile()) {
            throw new AndroidLocationException(
                    String.format("%1$s is not a valid folder.", avdRoot));
        } else if (folder.exists() == false) {
            // folder is not there, we create it and return
            folder.mkdirs();
            return null;
        }
        
        File[] avds = folder.listFiles(new FilenameFilter() {
            public boolean accept(File parent, String name) {
                if (INI_NAME_PATTERN.matcher(name).matches()) {
                    // check it's a file and not a folder
                    boolean isFile = new File(parent, name).isFile();
                    return isFile;
                }

                return false;
            }
        });
        
        return avds;
    }

    /**
     * Computes the internal list of available AVDs
     * @param allList the list to contain all the AVDs
     * 
     * @throws AndroidLocationException if there's a problem getting android root directory.
     */
    private void buildAvdList(ArrayList<AvdInfo> allList) throws AndroidLocationException {
        File[] avds = buildAvdFilesList();
        if (avds != null) {
            for (File avd : avds) {
                AvdInfo info = parseAvdInfo(avd);
                if (info != null) {
                    allList.add(info);
                }
            }
        }
    }

    /**
     * Parses an AVD .ini file to create an {@link AvdInfo}.
     * 
     * @param path The path to the AVD .ini file
     * @param acceptError When false, an AVD that fails to load will be discarded and null will be
     *        returned. When true, such an AVD will be returned with an error description.
     * @return A new {@link AvdInfo} or null if the file is not valid or null if the AVD is not
     *         valid and acceptError is false.
     */
    private AvdInfo parseAvdInfo(File path) {
        Map<String, String> map = SdkManager.parsePropertyFile(path, mSdkLog);

        String avdPath = map.get(AVD_INFO_PATH);
        String targetHash = map.get(AVD_INFO_TARGET);

        IAndroidTarget target = null;
        File configIniFile = null;
        Map<String, String> properties = null;
        
        if (targetHash != null) {
            target = mSdk.getTargetFromHashString(targetHash);
        }

        // load the AVD properties.
        if (avdPath != null) {
            configIniFile = new File(avdPath, CONFIG_INI);
        }
        
        if (configIniFile != null) {
            properties = SdkManager.parsePropertyFile(configIniFile, mSdkLog);
        }

        // get name
        String name = path.getName();
        Matcher matcher = INI_NAME_PATTERN.matcher(path.getName());
        if (matcher.matches()) {
            name = matcher.group(1);
        }
        
        // check the image.sysdir are valid
        boolean validImageSysdir = true;
        if (properties != null) {
            String imageSysDir = properties.get(AVD_INI_IMAGES_1);
            if (imageSysDir != null) {
                File f = new File(mSdk.getLocation() + File.separator + imageSysDir);
                if (f.isDirectory() == false) {
                    validImageSysdir = false;
                } else {
                    imageSysDir = properties.get(AVD_INI_IMAGES_2);
                    if (imageSysDir != null) {
                        f = new File(mSdk.getLocation() + File.separator + imageSysDir);
                        if (f.isDirectory() == false) {
                            validImageSysdir = false;
                        }
                    }
                }
            }
        }

        AvdStatus status;
        
        if (avdPath == null) {
            status = AvdStatus.ERROR_PATH;
        } else if (configIniFile == null) {
            status = AvdStatus.ERROR_CONFIG;
        } else if (targetHash == null) {
            status = AvdStatus.ERROR_TARGET_HASH;
        } else if (target == null) {
            status = AvdStatus.ERROR_TARGET;
        } else if (properties == null) {
            status = AvdStatus.ERROR_PROPERTIES;
        } else if (validImageSysdir == false) {
            status = AvdStatus.ERROR_IMAGE_DIR;
        } else {
            status = AvdStatus.OK;
        }
        
        AvdInfo info = new AvdInfo(
                name,
                avdPath,
                targetHash,
                target,
                properties,
                status);
        
        return info;
    }

    /**
     * Writes a .ini file from a set of properties.
     * 
     * @param iniFile The file to generate.
     * @param values THe properties to place in the ini file.
     * @throws IOException if {@link FileWriter} fails to open, write or close the file.
     */
    private static void writeIniFile(File iniFile, Map<String, String> values)
            throws IOException {
        FileWriter writer = new FileWriter(iniFile);
        
        for (Entry<String, String> entry : values.entrySet()) {
            writer.write(String.format("%1$s=%2$s\n", entry.getKey(), entry.getValue()));
        }
        writer.close();
    }
    
    /**
     * Invokes the tool to create a new SD card image file.
     * 
     * @param toolLocation The path to the mksdcard tool.
     * @param size The size of the new SD Card, compatible with {@link #SDCARD_SIZE_PATTERN}.
     * @param location The path of the new sdcard image file to generate.
     * @param log The logger object, to report errors.
     * @return True if the sdcard could be created.
     */
    private boolean createSdCard(String toolLocation, String size, String location) {
        try {
            String[] command = new String[3];
            command[0] = toolLocation;
            command[1] = size;
            command[2] = location;
            Process process = Runtime.getRuntime().exec(command);
    
            ArrayList<String> errorOutput = new ArrayList<String>();
            ArrayList<String> stdOutput = new ArrayList<String>();
            int status = grabProcessOutput(process, errorOutput, stdOutput,
                    true /* waitForReaders */);

            if (status == 0) {
                return true;
            } else {
                for (String error : errorOutput) {
                    mSdkLog.error(null, error);
                }
            }

        } catch (InterruptedException e) {
            // pass, print error below
        } catch (IOException e) {
            // pass, print error below
        }
        
        mSdkLog.error(null, "Failed to create the SD card.");
        return false;
    }

    /**
     * Gets the stderr/stdout outputs of a process and returns when the process is done.
     * Both <b>must</b> be read or the process will block on windows.
     * @param process The process to get the ouput from
     * @param errorOutput The array to store the stderr output. cannot be null.
     * @param stdOutput The array to store the stdout output. cannot be null.
     * @param waitforReaders if true, this will wait for the reader threads. 
     * @return the process return code.
     * @throws InterruptedException
     */
    private int grabProcessOutput(final Process process, final ArrayList<String> errorOutput,
            final ArrayList<String> stdOutput, boolean waitforReaders)
            throws InterruptedException {
        assert errorOutput != null;
        assert stdOutput != null;
        // read the lines as they come. if null is returned, it's
        // because the process finished
        Thread t1 = new Thread("") { //$NON-NLS-1$
            @Override
            public void run() {
                // create a buffer to read the stderr output
                InputStreamReader is = new InputStreamReader(process.getErrorStream());
                BufferedReader errReader = new BufferedReader(is);

                try {
                    while (true) {
                        String line = errReader.readLine();
                        if (line != null) {
                            errorOutput.add(line);
                        } else {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // do nothing.
                }
            }
        };

        Thread t2 = new Thread("") { //$NON-NLS-1$
            @Override
            public void run() {
                InputStreamReader is = new InputStreamReader(process.getInputStream());
                BufferedReader outReader = new BufferedReader(is);

                try {
                    while (true) {
                        String line = outReader.readLine();
                        if (line != null) {
                            stdOutput.add(line);
                        } else {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // do nothing.
                }
            }
        };

        t1.start();
        t2.start();

        // it looks like on windows process#waitFor() can return
        // before the thread have filled the arrays, so we wait for both threads and the
        // process itself.
        if (waitforReaders) {
            try {
                t1.join();
            } catch (InterruptedException e) {
            }
            try {
                t2.join();
            } catch (InterruptedException e) {
            }
        }

        // get the return code from the process
        return process.waitFor();
    }

    /**
     * Removes an {@link AvdInfo} from the internal list.
     * 
     * @param avdInfo The {@link AvdInfo} to remove.
     * @return true if this {@link AvdInfo} was present and has been removed.
     */
    public boolean removeAvd(AvdInfo avdInfo) {
        synchronized (mAllAvdList) {
            if (mAllAvdList.remove(avdInfo)) {
                mValidAvdList = mBrokenAvdList = null;
                return true;
            }
        }
        
        return false;
    }

    /**
     * Updates an AVD with new path to the system image folders.
     * @param name the name of the AVD to update.
     * @throws IOException 
     * @throws AndroidLocationException 
     */
    public void updateAvd(String name) throws IOException, AndroidLocationException {
        // find the AVD to update. It should be be in the broken list.
        AvdInfo avd = null;
        synchronized (mAllAvdList) {
            for (AvdInfo info : mAllAvdList) {
                if (info.getName().equals(name)) {
                    avd = info;
                    break;
                }
            }
        }
        
        if (avd == null) {
            // not in the broken list, just return.
            mSdkLog.error(null, "There is no Android Virtual Device named '%s'.", name);
            return;
        }

        // get the properties. This is a unmodifiable Map.
        Map<String, String> oldProperties = avd.getProperties();

        // create a new map
        Map<String, String> properties = new HashMap<String, String>();
        properties.putAll(oldProperties);
        
        AvdStatus status;
        
        // create the path to the new system images.
        if (setImagePathProperties(avd.getTarget(), properties)) {
            if (properties.containsKey(AVD_INI_IMAGES_1)) {
                mSdkLog.printf("Updated '%1$s' with value '%2$s'\n", AVD_INI_IMAGES_1,
                        properties.get(AVD_INI_IMAGES_1));
            }
    
            if (properties.containsKey(AVD_INI_IMAGES_2)) {
                mSdkLog.printf("Updated '%1$s' with value '%2$s'\n", AVD_INI_IMAGES_2,
                        properties.get(AVD_INI_IMAGES_2));
            }
            
            status = AvdStatus.OK;
        } else {
            mSdkLog.error(null, "Unable to find non empty system images folders for %1$s", name);
            //FIXME: display paths to empty image folders?
            status = AvdStatus.ERROR_IMAGE_DIR;
        }

        // now write the config file
        File configIniFile = new File(avd.getPath(), CONFIG_INI);
        writeIniFile(configIniFile, properties);

        // finally create a new AvdInfo for this unbroken avd and add it to the list.
        // instead of creating the AvdInfo object directly we reparse it, to detect other possible
        // errors
        // FIXME: We may want to create this AvdInfo by reparsing the AVD instead. This could detect other errors.
        AvdInfo newAvd = new AvdInfo(
                name,
                avd.getPath(),
                avd.getTargetHash(),
                avd.getTarget(),
                properties,
                status);
        
        replaceAvd(avd, newAvd);
    }

    /**
     * Sets the paths to the system images in a properties map.
     * @param target the target in which to find the system images.
     * @param properties the properties in which to set the paths.
     * @return true if success, false if some path are missing.
     */
    private boolean setImagePathProperties(IAndroidTarget target, Map<String, String> properties) {
        properties.remove(AVD_INI_IMAGES_1);
        properties.remove(AVD_INI_IMAGES_2);
        
        try {
            String property = AVD_INI_IMAGES_1;
            
            // First the image folders of the target itself
            String imagePath = getImageRelativePath(target);
            if (imagePath != null) {
                properties.put(property, imagePath);
                property = AVD_INI_IMAGES_2;
            }
    
    
            // If the target is an add-on we need to add the Platform image as a backup.
            IAndroidTarget parent = target.getParent();
            if (parent != null) {
                imagePath = getImageRelativePath(parent);
                if (imagePath != null) {
                    properties.put(property, imagePath);
                }
            }
            
            // we need at least one path!
            return properties.containsKey(AVD_INI_IMAGES_1);
        } catch (InvalidTargetPathException e) {
            mSdkLog.error(e, e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Replaces an old {@link AvdInfo} with a new one in the lists storing them.
     * @param oldAvd the {@link AvdInfo} to remove.
     * @param newAvd the {@link AvdInfo} to add.
     */
    private void replaceAvd(AvdInfo oldAvd, AvdInfo newAvd) {
        synchronized (mAllAvdList) {
            mAllAvdList.remove(oldAvd);
            mAllAvdList.add(newAvd);
            mValidAvdList = mBrokenAvdList = null;
        }
    }
}
