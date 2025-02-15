/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiConfiguration.RANDOMIZATION_NONE;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_EAP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.util.ScanResultUtil;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.LocalLog;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.Clock;
import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiCarrierInfoManager;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigurationTestUtil;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.HSWanMetricsElement;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link PasspointNetworkNominateHelper}.
 */
@SmallTest
public class PasspointNetworkNominateHelperTest extends WifiBaseTest {
    // TODO(b/140763176): should extend WifiBaseTest, but if it does then it fails with NPE
    private static final int TEST_NETWORK_ID = 1;
    private static final int TEST_NETWORK_ID2 = 2;
    private static final String TEST_SSID1 = "ssid1";
    private static final String TEST_SSID2 = "ssid2";
    private static final String TEST_BSSID1 = "01:23:45:56:78:9a";
    private static final String TEST_BSSID2 = "23:12:34:90:81:12";
    private static final String TEST_FQDN1 = "test1.com";
    private static final String TEST_FQDN2 = "test2.com";
    private static final int TEST_UID = 5555;
    private static final String TEST_PACKAGE = "test.package.com";
    private static final WifiConfiguration TEST_CONFIG1 = generateWifiConfig(TEST_FQDN1);
    private static final WifiConfiguration TEST_CONFIG2 = generateWifiConfig(TEST_FQDN2);
    private static PasspointProvider sTestProvider1;
    private static PasspointProvider sTestProvider2;
    private static final long TEST_START_TIME_MS = 0;

    @Mock PasspointManager mPasspointManager;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock LocalLog mLocalLog;
    @Mock WifiCarrierInfoManager mWifiCarrierInfoManager;
    @Mock Resources mResources;
    @Mock Clock mClock;
    PasspointNetworkNominateHelper mNominateHelper;

    /**
     * Helper function for generating {@link WifiConfiguration} for testing.
     *
     * @param fqdn The FQDN associated with the configuration
     * @return {@link WifiConfiguration}
     */
    private static WifiConfiguration generateWifiConfig(String fqdn) {
        WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.generateWifiConfig(-1,
                TEST_UID, "\"PasspointTestSSID\"", true, true,
                fqdn, fqdn, SECURITY_EAP);

        return wifiConfiguration;
    }

    /**
     * Helper function for generating {@link PasspointProvider} for testing.
     *
     * @param config The WifiConfiguration associated with the provider
     * @return {@link PasspointProvider}
     */
    private static PasspointProvider generateProvider(WifiConfiguration config) {
        PasspointProvider provider = mock(PasspointProvider.class);
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(config.FQDN);
        passpointConfig.setHomeSp(homeSp);
        when(provider.getConfig()).thenReturn(passpointConfig);
        when(provider.getWifiConfig()).thenReturn(config);
        when(provider.isFromSuggestion()).thenReturn(false);
        when(provider.isAutojoinEnabled()).thenReturn(true);
        return provider;
    }

    /**
     * Helper function for generating {@link ScanDetail} for testing.
     *
     * @param ssid The SSID associated with the scan
     * @param bssid The BSSID associated with the scan
     * @return {@link ScanDetail}
     */
    private ScanDetail generateScanDetail(String ssid, String bssid) {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(true);
        when(networkDetail.getHSRelease()).thenReturn(NetworkDetail.HSRelease.R1);
        when(networkDetail.getAnt()).thenReturn(NetworkDetail.Ant.FreePublic);
        when(networkDetail.isInternet()).thenReturn(true);

        ScanDetail scanDetail = mock(ScanDetail.class);
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.BSSID = bssid;
        when(scanDetail.getSSID()).thenReturn(ssid);
        when(scanDetail.getBSSIDString()).thenReturn(bssid);
        when(scanDetail.getScanResult()).thenReturn(scanResult);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);
        long currentTime = mClock.getWallClockMillis();
        when(scanDetail.getSeen()).thenReturn(currentTime);
        when(scanDetail.toKeyString()).thenReturn(bssid);
        return scanDetail;
    }

    private void advanceClockMs(long millis) {
        long currentTimeMs = mClock.getWallClockMillis();
        when(mClock.getWallClockMillis()).thenReturn(currentTimeMs + millis);
    }

    /**
     * Test setup.
     */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        sTestProvider1 = generateProvider(TEST_CONFIG1);
        sTestProvider2 = generateProvider(TEST_CONFIG2);
        when(mResources.getStringArray(
                R.array.config_wifiPasspointUseApWanLinkStatusAnqpElementFqdnAllowlist))
                .thenReturn(new String[0]);

        mNominateHelper = new PasspointNetworkNominateHelper(mPasspointManager, mWifiConfigManager,
                mLocalLog, mWifiCarrierInfoManager, mResources, mClock);
        // Always assume Passpoint is enabled as we don't disable it in the test.
        when(mPasspointManager.isWifiPasspointEnabled()).thenReturn(true);
        when(mClock.getWallClockMillis()).thenReturn(TEST_START_TIME_MS);
    }

    /**
     * Verify that no candidate will be nominated when evaluating scans without any matching
     * providers.
     */
    @Test
    public void evaluateScansWithNoMatch() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(null);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertTrue(candidates.isEmpty());
    }

    /**
     * Verify that provider matching will not be performed when evaluating scans with no
     * interworking support, verify that no candidate will be nominated.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNoInterworkingAP() throws Exception {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(false);
        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);

        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {scanDetail});
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertTrue(candidates.isEmpty());
        // Verify that no provider matching is performed.
        verify(mPasspointManager, never()).matchProvider(any(ScanResult.class));
    }

    /**
     * Verify that when a network matches a home provider is found, the correct network
     * information (WifiConfiguration) is setup and nominated.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNetworkMatchingHomeProvider() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        when(mWifiCarrierInfoManager.shouldDisableMacRandomization(anyString(), anyInt(), anyInt()))
                .thenReturn(true);

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt(), any(),
                eq(false));
        assertEquals(ScanResultUtil.createQuotedSsid(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertTrue(addedConfig.getValue().isHomeProviderNetwork);
        assertEquals(RANDOMIZATION_NONE, addedConfig.getValue().macRandomizationSetting);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), eq(TEST_UID), any());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));

        // When Scan results time out, should be not candidate return.
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        candidates = mNominateHelper
                .getPasspointNetworkCandidates(Collections.emptyList());
        assertTrue(candidates.isEmpty());
    }

    /**
     * Verify that when a network matches a home provider is found, the correct network
     * information (WifiConfiguration) is setup and nominated even if the scan result does not
     * report internet connectivity.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNoInternetBit() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        for (ScanDetail scanDetail : scanDetails) {
            when(scanDetail.getNetworkDetail().isInternet()).thenReturn(false);
        }

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt(), any(),
                eq(false));
        assertEquals(ScanResultUtil.createQuotedSsid(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertTrue(addedConfig.getValue().isHomeProviderNetwork);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), eq(TEST_UID), any());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));
    }

    /**
     * Verify that when a network matches a roaming provider is found, the correct network
     * information (WifiConfiguration) is setup and nominated.
     */
    @Test
    public void evaluateScansWithNetworkMatchingRoamingProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> roamingProvider = new ArrayList<>();
        roamingProvider.add(Pair.create(sTestProvider1, PasspointMatch.RoamingProvider));

        // Return roamingProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(roamingProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(), any(),
                eq(false)))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt(), any(),
                eq(false));
        assertEquals(ScanResultUtil.createQuotedSsid(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertFalse(addedConfig.getValue().isHomeProviderNetwork);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), eq(TEST_UID), any());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));
    }

    /**
     * Verify that when a network matches a roaming provider is found for different scanDetails,
     * will nominate both as the candidates.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithHomeProviderNetworkAndRoamingProviderNetwork() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        List<Pair<PasspointProvider, PasspointMatch>> roamingProvider = new ArrayList<>();
        roamingProvider.add(Pair.create(sTestProvider2, PasspointMatch.RoamingProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and
        // roamingProvider for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class)))
                .thenReturn(homeProvider).thenReturn(roamingProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + 1));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID + 1))
                .thenReturn(TEST_CONFIG2);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(2, candidates.size());

        verify(mWifiConfigManager, times(2))
                .addOrUpdateNetwork(any(), anyInt(), any(), eq(false));
    }

    /**
     * Verify that anonymous identity is empty when matching a SIM credential provider with a
     * network that supports encrypted IMSI and anonymous identity. The anonymous identity will be
     * populated with {@code anonymous@<realm>} by ClientModeImpl's handling of the
     * CMD_START_CONNECT event.
     */
    @Test
    public void evaluateSIMProviderWithNetworkSupportingEncryptedIMSI() {
        // Setup ScanDetail and match providers.
        List<ScanDetail> scanDetails = Collections.singletonList(
                generateScanDetail(TEST_SSID1, TEST_BSSID1));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);
        config.networkId = TEST_NETWORK_ID;
        PasspointProvider testProvider = generateProvider(config);
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(testProvider, PasspointMatch.HomeProvider));
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(testProvider.isSimCredential()).thenReturn(true);
        // SIM is present
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList())
                .thenReturn(Arrays.asList(mock(SubscriptionInfo.class)));
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(config);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());

        assertEquals("", candidates.get(0).second.enterpriseConfig.getAnonymousIdentity());
        assertTrue(candidates.get(0).second.enterpriseConfig.isAuthenticationSimBased());
    }

    /**
     * Verify that when the current active network is matched, the scan info associated with
     * the network is updated.
     */
    @Test
    public void evaluateScansMatchingActiveNetworkWithDifferentBSS() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID2));
        // Setup matching provider.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Setup currently connected network.
        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = TEST_NETWORK_ID;
        currentNetwork.SSID = ScanResultUtil.createQuotedSsid(TEST_SSID1);
        String currentBssid = TEST_BSSID1;

        // Match the current connected network to a home provider.
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(currentNetwork);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);

        assertEquals(1, candidates.size());
        ArgumentCaptor<ScanDetail> updatedCandidateScanDetail =
                ArgumentCaptor.forClass(ScanDetail.class);
        verify(mWifiConfigManager).updateScanDetailForNetwork(eq(TEST_NETWORK_ID),
                updatedCandidateScanDetail.capture());
        assertEquals(TEST_BSSID2, updatedCandidateScanDetail.getValue().getBSSIDString());
    }

    /**
     * Verify that the current configuration for the passpoint network is disabled, it will not
     * nominated that network.
     */
    @Test
    public void evaluateNetworkWithDisabledWifiConfig() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));

        WifiConfiguration disableConfig = new WifiConfiguration();
        WifiConfiguration.NetworkSelectionStatus selectionStatus =
                new WifiConfiguration.NetworkSelectionStatus();
        selectionStatus.setNetworkSelectionDisableReason(
                WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);
        selectionStatus.setNetworkSelectionStatus(
                WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        disableConfig.setNetworkSelectionStatus(selectionStatus);
        disableConfig.networkId = TEST_NETWORK_ID;
        TEST_CONFIG1.networkId = TEST_NETWORK_ID;

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.getConfiguredNetwork(anyString())).thenReturn(disableConfig);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        verify(mWifiConfigManager, never()).addOrUpdateNetwork(any(WifiConfiguration.class),
                anyInt(), any(), eq(false));
        assertTrue(candidates.isEmpty());
    }

    /**
     * Verify that when a network matching a home provider is found, but the network was
     * disconnected previously by user, it will not nominated that network.
     */
    @Test
    public void evaluateScanResultWithHomeMatchButPreviouslyUserDisconnected() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1).
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.isNetworkTemporarilyDisabledByUser(TEST_FQDN1))
                .thenReturn(true);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertTrue(candidates.isEmpty());
    }

    /**
     * Verify that when the WAN metrics status is 'LINK_STATUS_DOWN', it will not return as
     * candidate when use wan link status.
     */
    @Test
    public void evaluateScansWithNetworkMatchingHomeProviderUseAnqpLinkDown() throws Exception {
        when(mResources.getBoolean(R.bool.config_wifiPasspointUseApWanLinkStatusAnqpElement))
                .thenReturn(true);
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + 1));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(TEST_CONFIG1);
        // Setup WAN metrics status is 'LINK_STATUS_DOWN'
        HSWanMetricsElement wm = mock(HSWanMetricsElement.class);
        Map<ANQPElementType, ANQPElement> anqpElements = new HashMap<>();
        anqpElements.put(ANQPElementType.HSWANMetrics, wm);
        when(mPasspointManager.getANQPElements(scanDetails.get(0).getScanResult()))
                .thenReturn(anqpElements);
        when(wm.getStatus()).thenReturn(HSWanMetricsElement.LINK_STATUS_DOWN);
        when(wm.isElementInitialized()).thenReturn(true);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());
    }

    /**
     * Verify that when the WAN metrics status is 'LINK_STATUS_DOWN', it will return as
     * candidate when not use wan link status.
     */

    @Test
    public void evaluateScansWithNetworkMatchingHomeProviderNotUseAnqpLinkDown() throws Exception {
        when(mResources.getBoolean(R.bool.config_wifiPasspointUseApWanLinkStatusAnqpElement))
                .thenReturn(false);
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + 1));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(TEST_CONFIG1);
        // Setup WAN metrics status is 'LINK_STATUS_DOWN'
        HSWanMetricsElement wm = mock(HSWanMetricsElement.class);
        Map<ANQPElementType, ANQPElement> anqpElements = new HashMap<>();
        anqpElements.put(ANQPElementType.HSWANMetrics, wm);
        when(mPasspointManager.getANQPElements(scanDetails.get(0).getScanResult()))
                .thenReturn(anqpElements);
        when(wm.getStatus()).thenReturn(HSWanMetricsElement.LINK_STATUS_DOWN);
        when(wm.isElementInitialized()).thenReturn(true);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(2, candidates.size());
    }

    /**
     * Verify that when the WAN metrics status is 'LINK_STATUS_DOWN', it will not return as
     * candidate when not use wan link status but FQDN in the allow list
     */

    @Test
    public void evaluateScansWithNetworkMatchingHomeProviderNotUseAnqpLinkDownInFqdnList()
            throws Exception {
        when(mResources.getBoolean(R.bool.config_wifiPasspointUseApWanLinkStatusAnqpElement))
                .thenReturn(false);
        when(mResources.getStringArray(
                R.array.config_wifiPasspointUseApWanLinkStatusAnqpElementFqdnAllowlist))
                .thenReturn(new String[]{TEST_FQDN1});
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + 1));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(TEST_CONFIG1);
        // Setup WAN metrics status is 'LINK_STATUS_DOWN'
        HSWanMetricsElement wm = mock(HSWanMetricsElement.class);
        Map<ANQPElementType, ANQPElement> anqpElements = new HashMap<>();
        anqpElements.put(ANQPElementType.HSWANMetrics, wm);
        when(mPasspointManager.getANQPElements(scanDetails.get(0).getScanResult()))
                .thenReturn(anqpElements);
        when(wm.getStatus()).thenReturn(HSWanMetricsElement.LINK_STATUS_DOWN);
        when(wm.isElementInitialized()).thenReturn(true);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());
    }

    /**
     * Verify that when same provider is match home and roaming for different scanDetail,
     * the home provider matched scanDetail will be chosen.
     */

    @Test
    public void evaluateScansWithNetworkMatchingBothHomeAndRoamingForSameProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));

        // Setup matching providers for ScanDetail.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        List<Pair<PasspointProvider, PasspointMatch>> roamingProvider = new ArrayList<>();
        roamingProvider.add(Pair.create(sTestProvider1, PasspointMatch.RoamingProvider));
        // Return homeProvider for the first ScanDetail (TEST_SSID1) and
        // roamingProvider for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class)))
                .thenReturn(roamingProvider).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        // verify Only home provider matched candidate will by chosen
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).second.isHomeProviderNetwork);
        assertEquals(ScanResultUtil.createQuotedSsid(TEST_SSID2), candidates.get(0).second.SSID);
    }

    /**
     * For multiple scanDetails with matched providers, for each scanDetail nominate the best
     * providers: if home available, return all home providers; otherwise return all roaming
     * providers.
     * ScanDetail1 matches home providerA, scanDetail2 matches roaming providerB, will nominate both
     * matched pairs.
     */
    @Test
    public void evaluateScansWithNetworkMatchingBothHomeAndRoamingForDifferentProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2));
        // Setup matching providers for ScanDetail.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        List<Pair<PasspointProvider, PasspointMatch>> roamingProvider = new ArrayList<>();
        roamingProvider.add(Pair.create(sTestProvider2, PasspointMatch.RoamingProvider));
        // Return homeProvider for the first ScanDetail (TEST_SSID1) and
        // roamingProvider for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class)))
                .thenReturn(homeProvider).thenReturn(roamingProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID2));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID2)).thenReturn(TEST_CONFIG2);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        // Nominate matched home provider for first ScanDetail (TEST_SSID1) and roaming provider for
        // the second (TEST_SSID2).
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(c -> c.second.isHomeProviderNetwork
                && c.second.SSID.equals(ScanResultUtil.createQuotedSsid(TEST_SSID1))));
        assertTrue(candidates.stream().anyMatch(c -> !c.second.isHomeProviderNetwork
                && c.second.SSID.equals(ScanResultUtil.createQuotedSsid(TEST_SSID2))));
    }

    /**
     * For multiple matched providers from suggestion and user saved, return right ones according to
     * the request.
     */
    @Test
    public void evaluateScansWithNetworkMatchingProviderBothFromSavedAndSuggestion() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));
        // Setup matching providers for ScanDetail. One provider is saved, the other is suggestion.
        WifiConfiguration suggestionConfig = generateWifiConfig(TEST_FQDN2);
        suggestionConfig.creatorUid = TEST_UID;
        suggestionConfig.creatorName = TEST_PACKAGE;
        suggestionConfig.fromWifiNetworkSuggestion = true;
        PasspointProvider suggestionProvider = generateProvider(suggestionConfig);
        when(suggestionProvider.isFromSuggestion()).thenReturn(true);

        List<Pair<PasspointProvider, PasspointMatch>> homeProviders = new ArrayList<>();
        homeProviders.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        homeProviders.add(Pair.create(suggestionProvider, PasspointMatch.HomeProvider));
        when(mPasspointManager.matchProvider(any(ScanResult.class)))
                .thenReturn(homeProviders);
        when(mWifiConfigManager.addOrUpdateNetwork(eq(TEST_CONFIG1), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.addOrUpdateNetwork(eq(suggestionConfig), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID2));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID2))
                .thenReturn(suggestionConfig);
        //Get both passpoint network candidate
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(2, candidates.size());
    }

    /**
     * Verify that provider matching will not be performed when evaluating scans with interworking
     * support, but no HS2.0 VSA element with release version, verify that no candidate will be
     * nominated.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithInterworkingAndNoHs20VsaAP() throws Exception {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(true);
        when(networkDetail.getHSRelease()).thenReturn(null);
        when(networkDetail.getAnt()).thenReturn(NetworkDetail.Ant.FreePublic);
        when(networkDetail.isInternet()).thenReturn(true);
        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);

        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {scanDetail});
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertTrue(candidates.isEmpty());
        // Verify that no provider matching is performed.
        verify(mPasspointManager, never()).matchProvider(any(ScanResult.class));
    }

    /**
     * Verify matching passpoint provider with ChargeablePublic AP will nominate a metered
     * candidate.
     */
    @Test
    public void evaluateScansWithAntIsChargeablePublic() {
        ScanDetail scanDetail = generateScanDetail(TEST_SSID1, TEST_BSSID1);
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        when(networkDetail.getAnt()).thenReturn(NetworkDetail.Ant.ChargeablePublic);
        List<ScanDetail> scanDetails = Arrays.asList(scanDetail);

        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());
        assertTrue(WifiConfiguration.isMetered(candidates.get(0).second, null));
    }

    /**
     * Verify that when the WAN Metrics ANQP element is not initialized (all 0's), then the logic
     * ignores this element.
     */
    @Test
    public void evaluateScansWithNetworkMatchingHomeProviderWithUninitializedWanMetricsAnqpElement()
            throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);

        // Setup an uninitialized WAN Metrics element (or initialized with 0's)
        ByteBuffer buffer = ByteBuffer.allocate(HSWanMetricsElement.EXPECTED_BUFFER_SIZE);
        buffer.put(new byte[HSWanMetricsElement.EXPECTED_BUFFER_SIZE]);
        buffer.position(0);
        HSWanMetricsElement wanMetricsElement = HSWanMetricsElement.parse(buffer);
        Map<ANQPElementType, ANQPElement> anqpElements = new HashMap<>();
        anqpElements.put(ANQPElementType.HSWANMetrics, wanMetricsElement);
        when(mPasspointManager.getANQPElements(any(ScanResult.class)))
                .thenReturn(anqpElements);

        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());
    }

    @Test
    public void testRefreshPasspointNetworkCandidatesUsesCachedScans() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));

        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        // No profiles have been added, so expect the first candidate matching to return nothing.
        assertEquals(mNominateHelper.getPasspointNetworkCandidates(
                scanDetails).size(), 0);
        verify(mPasspointManager, times(1)).matchProvider(any());

        // Add a homeProvider for the scan detail passed in earlier
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);

        // Refreshing the network candidates with the cached scans should now result in a match
        mNominateHelper.refreshWifiConfigsForProviders();
        verify(mPasspointManager, times(2)).matchProvider(any());
        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt(), any(),
                eq(false));
        assertEquals(ScanResultUtil.createQuotedSsid(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertTrue(addedConfig.getValue().isHomeProviderNetwork);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), eq(TEST_UID), any());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));

        // Timeout the scan detail and verify we don't try to match the scan detail again.
        advanceClockMs(PasspointNetworkNominateHelper.SCAN_DETAIL_EXPIRATION_MS);
        mNominateHelper.refreshWifiConfigsForProviders();
        verify(mPasspointManager, times(2)).matchProvider(any());
        verify(mWifiConfigManager, times(1)).addOrUpdateNetwork(any(), anyInt(),
                any(), eq(false));
    }

    /**
     * Verify that when the WAN metrics status is 'LINK_STATUS_DOWN', it should be added to the
     * WifiConfigManager and NOT set No Internet.
     */
    @Test
    public void updateScansWithNetworkMatchingHomeProviderWithAnqpLinkDown() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> homeProvider = new ArrayList<>();
        homeProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(TEST_CONFIG1);
        // Setup WAN metrics status is 'LINK_STATUS_DOWN'
        HSWanMetricsElement wm = mock(HSWanMetricsElement.class);
        Map<ANQPElementType, ANQPElement> anqpElements = new HashMap<>();
        anqpElements.put(ANQPElementType.HSWANMetrics, wm);
        when(mPasspointManager.getANQPElements(any(ScanResult.class)))
                .thenReturn(anqpElements);
        when(wm.getStatus()).thenReturn(HSWanMetricsElement.LINK_STATUS_DOWN);
        when(wm.isElementInitialized()).thenReturn(true);

        mNominateHelper.updatePasspointConfig(scanDetails);
        verify(mWifiConfigManager, never())
                .incrementNetworkNoInternetAccessReports(eq(TEST_NETWORK_ID));
        verify(mWifiConfigManager, never()).updateNetworkSelectionStatus(eq(TEST_NETWORK_ID),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_PERMANENT));
    }

    /**
     * Verify that both home and roaming providers for same scan detail will be added to
     * WifiConfigManager.
     */
    @Test
    public void updateScansWithBothHomeProviderAndRoamingProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> matchedProvider = new ArrayList<>();
        matchedProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        matchedProvider.add(Pair.create(sTestProvider2, PasspointMatch.RoamingProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(matchedProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID2));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID2)).thenReturn(TEST_CONFIG2);
        mNominateHelper.updatePasspointConfig(scanDetails);
        verify(mWifiConfigManager, times(2))
                .addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(), any(), eq(false));
        verify(mWifiConfigManager)
                .enableNetwork(eq(TEST_NETWORK_ID), eq(false), anyInt(), any());
        verify(mWifiConfigManager)
                .enableNetwork(eq(TEST_NETWORK_ID2), eq(false), anyInt(), any());
        verify(mWifiConfigManager)
                .updateScanDetailForNetwork(eq(TEST_NETWORK_ID), eq(scanDetails.get(0)));
        verify(mWifiConfigManager)
                .updateScanDetailForNetwork(eq(TEST_NETWORK_ID2), eq(scanDetails.get(0)));
    }

    /**
     * Verify when ScanDetails matches both home and roaming providers, only home provider will be
     * return as candidate.
     */
    @Test
    public void evaluateScansWithBothHomeProviderAndRoamingProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(generateScanDetail(TEST_SSID1, TEST_BSSID1));
        // Setup matching providers for ScanDetail with TEST_SSID1.
        List<Pair<PasspointProvider, PasspointMatch>> matchedProvider = new ArrayList<>();
        matchedProvider.add(Pair.create(sTestProvider1, PasspointMatch.HomeProvider));
        matchedProvider.add(Pair.create(sTestProvider2, PasspointMatch.RoamingProvider));

        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(matchedProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false))).thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID2));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID2)).thenReturn(TEST_CONFIG2);
        List<Pair<ScanDetail, WifiConfiguration>> candidates = mNominateHelper
                .getPasspointNetworkCandidates(scanDetails);
        assertEquals(1, candidates.size());
        verify(mWifiConfigManager).addOrUpdateNetwork(any(WifiConfiguration.class), anyInt(),
                any(), eq(false));
        assertTrue(candidates.get(0).second.isHomeProviderNetwork);
    }
}
