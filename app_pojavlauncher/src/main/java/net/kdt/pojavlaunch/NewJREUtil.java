package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Architecture.archAsString;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.MathUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class NewJREUtil {

    private static boolean checkInternalRuntime(
            AssetManager assetManager,
            InternalRuntime internalRuntime) {

        String launcherRuntimeVersion;
        String installedRuntimeVersion =
                MultiRTUtils.readInternalRuntimeVersion(internalRuntime.name);

        try {
            launcherRuntimeVersion =
                    Tools.read(assetManager.open(internalRuntime.path + "/version"));
        } catch (IOException exc) {
            return installedRuntimeVersion != null;
        }

        if (!launcherRuntimeVersion.equals(installedRuntimeVersion)) {
            return unpackInternalRuntime(
                    assetManager, internalRuntime, launcherRuntimeVersion);
        }

        return true;
    }

    private static boolean unpackInternalRuntime(
            AssetManager assetManager,
            InternalRuntime internalRuntime,
            String version) {

        try {
            MultiRTUtils.installRuntimeNamedBinpack(
                    assetManager.open(internalRuntime.path + "/universal.tar.xz"),
                    assetManager.open(
                            internalRuntime.path + "/bin-"
                                    + archAsString(Tools.DEVICE_ARCHITECTURE)
                                    + ".tar.xz"),
                    internalRuntime.name,
                    version);

            MultiRTUtils.postPrepare(internalRuntime.name);
            return true;

        } catch (IOException e) {
            Log.e("NewJREAuto", "Internal JRE unpack failed", e);
            return false;
        }
    }

    private static InternalRuntime getInternalRuntime(Runtime runtime) {
        for (InternalRuntime internalRuntime : InternalRuntime.values()) {
            if (internalRuntime.name.equals(runtime.name)) {
                return internalRuntime;
            }
        }
        return null;
    }

    private static MathUtils.RankedValue<Runtime> getNearestInstalledRuntime(
            int targetVersion) {

        List<Runtime> runtimes = MultiRTUtils.getRuntimes();

        return MathUtils.findNearestPositive(
                targetVersion,
                runtimes,
                runtime -> runtime.javaVersion);
    }

    private static MathUtils.RankedValue<InternalRuntime> getNearestInternalRuntime(
            int targetVersion) {

        List<InternalRuntime> runtimeList =
                Arrays.asList(InternalRuntime.values());

        return MathUtils.findNearestPositive(
                targetVersion,
                runtimeList,
                runtime -> runtime.majorVersion);
    }

    public static boolean installNewJreIfNeeded(
            Activity activity,
            JMinecraftVersionList.Version versionInfo) {

        if (versionInfo.javaVersion == null
                || versionInfo.javaVersion.component.equalsIgnoreCase("jre-legacy")) {
            return true;
        }

        int requiredVersion = versionInfo.javaVersion.majorVersion;

        LauncherProfiles.load();

        AssetManager assetManager = activity.getAssets();
        MinecraftProfile minecraftProfile =
                LauncherProfiles.getCurrentProfile();

        String profileRuntime = Tools.getSelectedRuntime(minecraftProfile);
        Runtime runtime = MultiRTUtils.read(profileRuntime);

        if (runtime.javaVersion >= requiredVersion) {
            InternalRuntime internalRuntime = getInternalRuntime(runtime);

            if (internalRuntime != null) {
                return checkInternalRuntime(assetManager, internalRuntime);
            }

            return true;
        }

        MathUtils.RankedValue<Runtime> nearestInstalledRuntime =
                getNearestInstalledRuntime(requiredVersion);

        MathUtils.RankedValue<InternalRuntime> nearestInternalRuntime =
                getNearestInternalRuntime(requiredVersion);

        MathUtils.RankedValue<?> selectedRuntime = MathUtils.objectMin(
                nearestInternalRuntime,
                nearestInstalledRuntime,
                value -> value.rank);

        if (selectedRuntime != null
                && selectedRuntime.value instanceof InternalRuntime) {

            InternalRuntime internalRuntime =
                    (InternalRuntime) selectedRuntime.value;

            if (!checkInternalRuntime(assetManager, internalRuntime)) {
                if (nearestInstalledRuntime == null) {
                    if (!isJavaVersionAvailableForDownload(requiredVersion)) {
                        showRuntimeFail(activity, versionInfo);
                        return false;
                    }

                    try {
