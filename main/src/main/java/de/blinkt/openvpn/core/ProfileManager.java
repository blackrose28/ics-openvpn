/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import de.blinkt.openvpn.VpnProfile;

public class ProfileManager {
    private static final String PREFS_NAME = "VPNList";

    private static final String LAST_CONNECTED_PROFILE = "lastConnectedProfile";
    private static final String TEMPORARY_PROFILE_FILENAME = "temporary-vpn-profile";
    private static ProfileManager instance;

    private static VpnProfile mLastConnectedVpn = null;
    private static VpnProfile tmpprofile = null;
    private HashMap<String, VpnProfile> profiles = new HashMap<>();
    /* We got an error trying to save profiles, do not try encryption anymore */
    private static boolean encryptionBroken = false;

    private ProfileManager() {
    }

    private static VpnProfile get(String key) {
        if (tmpprofile != null && tmpprofile.getUUIDString().equals(key))
            return tmpprofile;

        if (instance == null)
            return null;
        return instance.profiles.get(key);
    }

    private synchronized static void checkInstance(Context context) {
        if (instance == null) {
            instance = new ProfileManager();
            ProfileEncryption.initMasterCryptAlias(context);
            instance.loadVPNList(context);
        }
    }

    synchronized public static ProfileManager getInstance(Context context) {
        checkInstance(context);
        return instance;
    }

    public static void setConnectedVpnProfileDisconnected(Context c) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);
        Editor prefsedit = prefs.edit();
        prefsedit.putString(LAST_CONNECTED_PROFILE, null);
        prefsedit.apply();
    }

    /**
     * Sets the profile that is connected (to connect if the service restarts)
     */
    public static void setConnectedVpnProfile(Context c, VpnProfile connectedProfile) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);
        Editor prefsedit = prefs.edit();

        prefsedit.putString(LAST_CONNECTED_PROFILE, connectedProfile.getUUIDString());
        prefsedit.apply();
        mLastConnectedVpn = connectedProfile;
    }

    /**
     * Returns the profile that was last connected (to connect if the service restarts)
     */
    public static VpnProfile getLastConnectedProfile(Context c) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(c);

        String lastConnectedProfile = prefs.getString(LAST_CONNECTED_PROFILE, null);
        if (lastConnectedProfile != null)
            return get(c, lastConnectedProfile);
        else
            return null;
    }

    public static void setTemporaryProfile(Context c, VpnProfile tmp) {
        tmp.mTemporaryProfile = true;
        ProfileManager.tmpprofile = tmp;
        tmp.addChangeLogEntry("temporary profile saved");
        saveProfile(c, tmp);
    }

    public static boolean isTempProfile() {
        return mLastConnectedVpn != null && mLastConnectedVpn == tmpprofile;
    }

    public static void saveProfile(Context context, VpnProfile profile) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);
        boolean preferEncryption = prefs.getBoolean("preferencryption", true);
        if (encryptionBroken)
            preferEncryption = false;

        profile.mVersion += 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            profile.addChangeLogEntry("Saving from version " + profile.mVersion +
                    " from process " + Application.getProcessName());
        }
        ObjectOutputStream vpnFile;

        String filename = profile.getUUID().toString();

        if (profile.mTemporaryProfile)
            filename = TEMPORARY_PROFILE_FILENAME;

        File encryptedFileOld = context.getFileStreamPath(filename + ".cpold");

        if (encryptedFileOld.exists())
        {
            encryptedFileOld.delete();
        }

        String deleteIfExists;
        try {
            FileOutputStream vpnFileOut;
            if (preferEncryption && ProfileEncryption.encryptionEnabled()) {
                File encryptedFile = context.getFileStreamPath(filename + ".cp");

                if (encryptedFile.exists())
                {
                    if (!encryptedFile.renameTo(encryptedFileOld))
                    {
                        VpnStatus.logInfo("Cannot rename " + encryptedFile);
                    }
                }
                try {
                    vpnFileOut = ProfileEncryption.getEncryptedVpOutput(context, encryptedFile);
                    deleteIfExists = filename + ".vp";
                    if (encryptedFileOld.exists()) {
                        encryptedFileOld.delete();
                    }
                } catch (IOException | GeneralSecurityException ioe)
                {
                    VpnStatus.logException(VpnStatus.LogLevel.INFO, "Error trying to write an encrypted VPN profile, disabling " +
                            "encryption", ioe);
                    encryptionBroken = true;
                    saveProfile(context, profile);
                    return;
                }
            }
            else {
                vpnFileOut = context.openFileOutput(filename + ".vp", Activity.MODE_PRIVATE);
                deleteIfExists = filename + ".cp";
            }

            vpnFile = new ObjectOutputStream(vpnFileOut);

            vpnFile.writeObject(profile);
            vpnFile.flush();
            vpnFile.close();

            File delete = context.getFileStreamPath(deleteIfExists);
            if (delete.exists())
            {
                //noinspection ResultOfMethodCallIgnored
                delete.delete();
            }
            VpnStatus.notifyProfileVersionChanged(profile.getUUIDString(), profile.mVersion, true);
        } catch (IOException e) {
            VpnStatus.logException("saving VPN profile", e);
            throw new RuntimeException(e);
        }
    }

    public static VpnProfile get(Context context, String profileUUID) {
        return get(context, profileUUID, 0, 10);
    }

    public static VpnProfile get(Context context, String profileUUID, int version, int tries) {
        checkInstance(context);
        VpnProfile profile = get(profileUUID);
        int tried = 0;
        while ((profile == null || profile.mVersion < version) && (tried++ < tries)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            instance.loadVPNList(context);
            profile = get(profileUUID);
        }

        if (tried > 5) {
            int ver = profile == null ? -1 : profile.mVersion;
            VpnStatus.logError(String.format(Locale.US, "Used x %d tries to get current version (%d/%d) of the profile", tried, ver, version));
        }
        return profile;
    }

    public static VpnProfile getLastConnectedVpn() {
        return mLastConnectedVpn;
    }

    public static VpnProfile getAlwaysOnVPN(Context context) {
        checkInstance(context);
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);

        String uuid = prefs.getString("alwaysOnVpn", null);
        return get(uuid);

    }

    public static void updateLRU(Context c, VpnProfile profile) {
        profile.mLastUsed = System.currentTimeMillis();
        // LRU does not change the profile, no need for the service to refresh
        if (profile != tmpprofile) {
            profile.addChangeLogEntry("Saved last recently used");
            saveProfile(c, profile);
        }
    }


    public static void notifyProfileVersionChanged(Context c, String uuid, int version) {
        /* The profile has been saved/modified. Potentially on the other process. We might need
         * to reload the profile from storage */

        VpnProfile loadedProfile = get(c, uuid, version, 100);
        if (loadedProfile != null && loadedProfile.mVersion >= version) {
            VpnStatus.notifyProfileVersionChanged(uuid, version, false);
        }
    }

    public Collection<VpnProfile> getProfiles() {
        return profiles.values();
    }

    public VpnProfile getProfileByName(String name) {
        for (VpnProfile vpnp : profiles.values()) {
            if (vpnp.getName().equals(name)) {
                return vpnp;
            }
        }
        return null;
    }

    public void saveProfileList(Context context) {
        SharedPreferences sharedprefs = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Editor editor = sharedprefs.edit();
        /* preference saving might be asynchronous*/
        final HashSet<String> vpn_list = new HashSet<>(profiles.keySet());
        /* The UI and the :openvpn service process each keep their own cached copy of this
         * preference (MODE_MULTI_PROCESS is ignored since API 24), so a write from a process
         * with a stale copy would drop profiles added by the other one. Never remove an entry
         * that still has a profile file on disk. */
        vpn_list.addAll(profileUuidsOnDisk(context));
        editor.putStringSet("vpnlist", vpn_list);

        // For reasons I do not understand at all
        // Android saves my prefs file only one time
        // if I remove the debug code below :(
        int counter = sharedprefs.getInt("counter", 0);
        editor.putInt("counter", counter + 1);
        editor.apply();
    }

    public synchronized void addProfile(VpnProfile profile) {
        profiles.put(profile.getUUID().toString(), profile);
    }

    /**
     * Checks if a profile has been added deleted since last loading and will update its
     * profiles
     * @param context
     */
    public synchronized void refreshVPNList(Context context)
    {
        Set<String> known = profileUuidsOnDisk(context);
        SharedPreferences listpref = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Set<String> vlist = listpref.getStringSet("vpnlist", null);
        if (vlist != null)
            known.addAll(vlist);

        for (String vpnentry : known) {
            if (!profiles.containsKey(vpnentry))
                loadVpnEntry(context, vpnentry);
        }

        Vector<String> removeUuids = new Vector<>();
        for (String profileuuid:profiles.keySet())
        {
            if (!known.contains(profileuuid))
                removeUuids.add(profileuuid);
        }
        for (String uuid: removeUuids)
        {
            profiles.remove(uuid);
        }
    }

    /**
     * Returns the UUIDs of the profiles that have a file in the app's private storage. The files
     * are the authoritative list of profiles: unlike the "vpnlist" preference they cannot be
     * lost to a stale SharedPreferences copy held by another process.
     */
    private Set<String> profileUuidsOnDisk(Context context) {
        Set<String> uuids = new HashSet<>();
        String[] extensions = new String[]{".cp", ".vp", ".cpold"};
        for (String file : context.fileList()) {
            for (String ext : extensions) {
                if (file.endsWith(ext)) {
                    uuids.add(file.substring(0, file.length() - ext.length()));
                    break;
                }
            }
        }
        return uuids;
    }

    private synchronized void loadVPNList(Context context) {
        profiles = new HashMap<>();
        SharedPreferences listpref = Preferences.getSharedPreferencesMulti(PREFS_NAME, context);
        Set<String> vlist = listpref.getStringSet("vpnlist", null);
        if (vlist == null)
            vlist = new HashSet<>();
        else
            /* getStringSet() hands out the instance owned by SharedPreferences, copy it so the
             * cached preference is not modified by accident */
            vlist = new HashSet<>(vlist);

        Set<String> entries = new HashSet<>(vlist);
        /* Always reconcile with the files on disk: the "vpnlist" preference cannot be trusted on
         * its own, because both processes cache it separately and the recovery of a missing entry
         * used to run only once per install. That left imported profiles invisible to the service
         * process (and to the next app start). */
        entries.addAll(profileUuidsOnDisk(context));
        entries.add(TEMPORARY_PROFILE_FILENAME);

        for (String vpnentry : entries) {
            loadVpnEntry(context, vpnentry);
        }

        if (!entries.equals(vlist))
            saveProfileList(context);
    }

    private synchronized void loadVpnEntry(Context context, String vpnentry) {
        ObjectInputStream vpnfile = null;
        try {
            FileInputStream vpInput;
            File encryptedPath = context.getFileStreamPath(vpnentry + ".cp");
            File encryptedPathOld = context.getFileStreamPath(vpnentry + ".cpold");

            if (encryptedPath.exists()) {
                vpInput = ProfileEncryption.getEncryptedVpInput(context, encryptedPath);
            } else if (encryptedPathOld.exists()) {
                vpInput = ProfileEncryption.getEncryptedVpInput(context, encryptedPathOld);
            } else {
                vpInput = context.openFileInput(vpnentry + ".vp");
            }
            vpnfile = new ObjectInputStream(vpInput);
            VpnProfile vp = ((VpnProfile) vpnfile.readObject());

            // Sanity check
            if (vp == null || vp.mName == null || vp.getUUID() == null)
                return;

            vp.upgradeProfile();
            if (vpnentry.equals(TEMPORARY_PROFILE_FILENAME)) {
                tmpprofile = vp;
            } else {
                profiles.put(vp.getUUID().toString(), vp);
            }
        } catch (IOException | ClassNotFoundException | GeneralSecurityException e) {
            if (!vpnentry.equals(TEMPORARY_PROFILE_FILENAME))
                VpnStatus.logException("Loading VPN List:" + vpnentry, e);
        } finally {
            if (vpnfile != null) {
                try {
                    vpnfile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public synchronized void removeProfile(Context context, VpnProfile profile) {
        String vpnentry = profile.getUUID().toString();
        profiles.remove(vpnentry);

        // delete both unencrypted and encrypted variants
        String[] extensions = new String[]{".vp", ".cp", ".cpold"};
        for (String ext: extensions)
            context.deleteFile(vpnentry + ext);

        /* After the files are gone, otherwise saveProfileList() keeps the UUID because entries
         * with a file on disk are never removed from the list */
        saveProfileList(context);

        if (mLastConnectedVpn == profile)
            mLastConnectedVpn = null;

    }
}
