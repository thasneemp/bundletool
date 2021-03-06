/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.tools.build.bundletool.commands;

import static com.android.tools.build.bundletool.testing.ApkSetUtils.extractTocFromApkSetFile;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createInstantBundle;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createLdpiHdpiAppBundle;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createMaxSdkBundle;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createMinMaxSdkAppBundle;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createMinSdkBundle;
import static com.android.tools.build.bundletool.testing.AppBundleFactory.createX86AppBundle;
import static com.android.tools.build.bundletool.testing.DeviceFactory.abis;
import static com.android.tools.build.bundletool.testing.DeviceFactory.createDeviceSpecFile;
import static com.android.tools.build.bundletool.testing.DeviceFactory.density;
import static com.android.tools.build.bundletool.testing.DeviceFactory.deviceWithSdk;
import static com.android.tools.build.bundletool.testing.DeviceFactory.lDeviceWithDensity;
import static com.android.tools.build.bundletool.testing.DeviceFactory.locales;
import static com.android.tools.build.bundletool.testing.DeviceFactory.mergeSpecs;
import static com.android.tools.build.bundletool.testing.DeviceFactory.sdkVersion;
import static com.android.tools.build.bundletool.testing.ManifestProtoUtils.androidManifest;
import static com.android.tools.build.bundletool.testing.truth.zip.TruthZip.assertThat;
import static com.android.tools.build.bundletool.utils.ResultUtils.instantApkVariants;
import static com.android.tools.build.bundletool.utils.ResultUtils.splitApkVariants;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.android.bundle.Commands.ApkDescription;
import com.android.bundle.Commands.ApkSet;
import com.android.bundle.Commands.BuildApksResult;
import com.android.bundle.Commands.Variant;
import com.android.bundle.Devices.DeviceSpec;
import com.android.bundle.Targeting.ScreenDensity.DensityAlias;
import com.android.tools.build.bundletool.device.AdbServer;
import com.android.tools.build.bundletool.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.exceptions.ValidationException;
import com.android.tools.build.bundletool.io.AppBundleSerializer;
import com.android.tools.build.bundletool.model.AppBundle;
import com.android.tools.build.bundletool.testing.AppBundleBuilder;
import com.android.tools.build.bundletool.testing.FakeAdbServer;
import com.android.tools.build.bundletool.utils.flags.FlagParser;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BuildApksDeviceSpecTest {

  private final AppBundleSerializer bundleSerializer = new AppBundleSerializer();

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();
  private Path tmpDir;

  private Path bundlePath;
  private Path outputDir;
  private Path outputFilePath;
  private final AdbServer fakeAdbServer =
      new FakeAdbServer(/* hasInitialDeviceList= */ true, /* devices= */ ImmutableList.of());

  @Before
  public void setUp() throws Exception {
    tmpDir = tmp.getRoot().toPath();
    bundlePath = tmpDir.resolve("bundle");
    outputDir = tmp.newFolder("output").toPath();
    outputFilePath = outputDir.resolve("app.apks");
  }

  @Test
  public void deviceSpec_flagsEquivalent() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(DeviceSpec.getDefaultInstance(), tmpDir.resolve("device.json"));
    BuildApksCommand commandViaFlags =
        BuildApksCommand.fromFlags(
            new FlagParser()
                .parse(
                    "--bundle=" + bundlePath,
                    "--output=" + outputFilePath,
                    "--device-spec=" + deviceSpecPath),
            fakeAdbServer);

    BuildApksCommand commandViaBuilder =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            // Optional values.
            .setDeviceSpecPath(deviceSpecPath)
            // Must copy instance of the internal executor service.
            .setExecutorServiceInternal(commandViaFlags.getExecutorService())
            .setExecutorServiceCreatedByBundleTool(true)
            .build();

    assertThat(commandViaBuilder).isEqualTo(commandViaFlags);
  }

  @Test
  public void deviceSpec_universalApk_throws() throws Exception {
    Path deviceSpecPath = createDeviceSpecFile(deviceWithSdk(21), tmpDir.resolve("device.json"));

    AppBundle appBundle =
        new AppBundleBuilder()
            .addModule("base", module -> module.setManifest(androidManifest("com.app")))
            .build();
    bundleSerializer.writeToDisk(appBundle, bundlePath);

    BuildApksCommand.Builder command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .setGenerateOnlyUniversalApk(true);

    Throwable exception = assertThrows(ValidationException.class, () -> command.build());
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Cannot generate universal APK and optimize for the device spec at the same time.");
  }

  @Test
  public void deviceSpec_andConnectedDevice_throws() throws Exception {
    Path deviceSpecPath = createDeviceSpecFile(deviceWithSdk(21), tmpDir.resolve("device.json"));

    AppBundle appBundle =
        new AppBundleBuilder()
            .addModule("base", module -> module.setManifest(androidManifest("com.app")))
            .build();
    bundleSerializer.writeToDisk(appBundle, bundlePath);

    BuildApksCommand.Builder command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .setGenerateOnlyForConnectedDevice(true);

    Throwable exception = assertThrows(ValidationException.class, () -> command.build());
    assertThat(exception)
        .hasMessageThat()
        .contains("Cannot optimize for the device spec and connected device at the same time.");
  }

  @Test
  public void deviceSpec_correctSplitsGenerated() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(lDeviceWithDensity(DensityAlias.XHDPI), tmpDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createLdpiHdpiAppBundle(), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Path apksArchive = command.execute();
    ZipFile apksZipFile = new ZipFile(apksArchive.toFile());
    assertThat(apksZipFile)
        .containsExactlyEntries("toc.pb", "splits/base-master.apk", "splits/base-xhdpi.apk");
    BuildApksResult result = extractTocFromApkSetFile(apksZipFile, outputDir);
    assertThat(result.getVariantList()).hasSize(1);
    Variant variant = result.getVariant(0);
    assertThat(variant.getApkSetList()).hasSize(1);
    ApkSet apkSet = variant.getApkSet(0);
    // One master and one density split.
    assertThat(apkSet.getApkDescriptionList()).hasSize(2);
    assertThat(apkNamesInSet(apkSet))
        .containsExactly("splits/base-master.apk", "splits/base-xhdpi.apk");
  }

  @Test
  public void deviceSpec_correctStandaloneGenerated() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(
            mergeSpecs(
                sdkVersion(19), abis("x86"),
                locales("en-US"), density(DensityAlias.HDPI)),
            tmpDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createLdpiHdpiAppBundle(), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Path apksArchive = command.execute();
    ZipFile apksZipFile = new ZipFile(apksArchive.toFile());
    assertThat(apksZipFile).containsExactlyEntries("toc.pb", "standalones/standalone-hdpi.apk");
    BuildApksResult result = extractTocFromApkSetFile(apksZipFile, outputDir);
    assertThat(result.getVariantList()).hasSize(1);
    Variant variant = result.getVariant(0);
    assertThat(variant.getApkSetList()).hasSize(1);
    ApkSet apkSet = variant.getApkSet(0);
    // One standalone APK.
    assertThat(apkSet.getApkDescriptionList()).hasSize(1);
    assertThat(apkNamesInSet(apkSet)).containsExactly("standalones/standalone-hdpi.apk");
  }

  @Test
  public void deviceSpecL_bundleTargetsPreL_throws() throws Exception {
    bundleSerializer.writeToDisk(createMaxSdkBundle(/* KitKat */ 19), bundlePath);

    Path deviceSpecPath =
        createDeviceSpecFile(
            lDeviceWithDensity(DensityAlias.XHDPI), outputDir.resolve("device.json"));

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Throwable exception = assertThrows(CommandExecutionException.class, () -> command.execute());
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "App Bundle targets pre-L devices, but the device has SDK version "
                + "higher or equal to L.");
  }

  @Test
  public void deviceSpecPreL_bundleTargetsLPlus_throws() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(
            mergeSpecs(
                sdkVersion(/* KitKat */ 19), abis("x86"),
                locales("en-US"), density(DensityAlias.XHDPI)),
            outputDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createMinSdkBundle(21), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Throwable exception = assertThrows(CommandExecutionException.class, () -> command.execute());
    assertThat(exception)
        .hasMessageThat()
        .contains("App Bundle targets L+ devices, but the device has SDK version lower than L.");
  }

  @Ignore("Re-enable when minSdk version propagation is fixed.")
  @Test
  public void deviceSpecL_bundleTargetsMPlus_throws() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(
            mergeSpecs(
                sdkVersion(/* Lollipop */ 21), abis("x86"),
                locales("en-US"), density(DensityAlias.XHDPI)),
            outputDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createMinSdkBundle(/* Marshmallow */ 23), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Throwable exception = assertThrows(CommandExecutionException.class, () -> command.execute());
    assertThat(exception).hasMessageThat().contains("");
  }

  @Test
  public void deviceSpecMips_bundleTargetsX86_throws() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(
            mergeSpecs(
                sdkVersion(/* Lollipop */ 21), abis("mips"),
                locales("en-US"), density(DensityAlias.XHDPI)),
            outputDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createX86AppBundle(), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Throwable exception = assertThrows(CommandExecutionException.class, () -> command.execute());
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "The app doesn't support ABI architectures of the device. Device ABIs: [mips], "
                + "app ABIs: [x86]");
  }

  @Ignore("Re-enable when maxSdkVersion is validated in App Bundle and used in device matching.")
  @Test
  public void deviceSpecN_bundleTargetsLtoM() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(
            mergeSpecs(
                sdkVersion(/* Nougat */ 25), abis("x86"),
                locales("en-US"), density(DensityAlias.XHDPI)),
            outputDir.resolve("device.json"));

    bundleSerializer.writeToDisk(
        createMinMaxSdkAppBundle(/* Lollipop */ 21, /* Marshmallow */ 23), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Throwable exception = assertThrows(CommandExecutionException.class, () -> command.execute());
    assertThat(exception).hasMessageThat().contains("... enter the validation message here ...");
  }

  @Test
  public void deviceSpec_instantSplitsGenerated() throws Exception {
    Path deviceSpecPath =
        createDeviceSpecFile(lDeviceWithDensity(DensityAlias.XHDPI), tmpDir.resolve("device.json"));

    bundleSerializer.writeToDisk(createInstantBundle(), bundlePath);

    BuildApksCommand command =
        BuildApksCommand.builder()
            .setBundlePath(bundlePath)
            .setOutputFile(outputFilePath)
            .setDeviceSpecPath(deviceSpecPath)
            .build();

    Path apksArchive = command.execute();
    ZipFile apksZipFile = new ZipFile(apksArchive.toFile());
    assertThat(apksZipFile)
        .containsExactlyEntries(
            "toc.pb", "splits/base-master.apk", "instant/instant-base-master.apk");
    BuildApksResult result = extractTocFromApkSetFile(apksZipFile, outputDir);
    assertThat(instantApkVariants(result)).hasSize(1);
    assertThat(splitApkVariants(result)).hasSize(1);

    Variant variant = splitApkVariants(result).get(0);
    assertThat(variant.getApkSetList()).hasSize(1);
    ApkSet apkSet = variant.getApkSet(0);
    assertThat(apkSet.getApkDescriptionList()).hasSize(1);
    assertThat(apkNamesInSet(apkSet)).containsExactly("splits/base-master.apk");

    variant = instantApkVariants(result).get(0);
    assertThat(variant.getApkSetList()).hasSize(1);
    apkSet = variant.getApkSet(0);
    assertThat(apkSet.getApkDescriptionList()).hasSize(1);
    assertThat(apkNamesInSet(apkSet)).containsExactly("instant/instant-base-master.apk");
  }

  private static ImmutableList<String> apkNamesInSet(ApkSet apkSet) {
    return apkSet
        .getApkDescriptionList()
        .stream()
        .map(ApkDescription::getPath)
        .collect(toImmutableList());
  }
}
