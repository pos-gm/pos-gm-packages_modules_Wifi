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

import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OPSTR_CHANGE_WIFI_STATE;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_METERED;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_EAP;

import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.net.Uri;
import android.net.wifi.EAPConstants;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Looper;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.LocalLog;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.Clock;
import com.android.server.wifi.DeviceConfigFacade;
import com.android.server.wifi.FakeKeys;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.MacAddressUtil;
import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.RunnerHandler;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiCarrierInfoManager;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiConfigurationTestUtil;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiNetworkSuggestionsManager;
import com.android.server.wifi.WifiPseudonymManager;
import com.android.server.wifi.WifiSettingsStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.HSOsuProvidersElement;
import com.android.server.wifi.hotspot2.anqp.I18Name;
import com.android.server.wifi.hotspot2.anqp.OsuProviderInfo;
import com.android.server.wifi.hotspot2.anqp.VenueNameElement;
import com.android.server.wifi.hotspot2.anqp.VenueUrlElement;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.InformationElementUtil.RoamingConsortium;
import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unit tests for {@link PasspointManager}.
 */
@SmallTest
public class PasspointManagerTest extends WifiBaseTest {
    private static final long BSSID = 0x112233445566L;
    private static final String TEST_PACKAGE = "com.android.test";
    private static final String TEST_PACKAGE1 = "com.android.test1";
    private static final String TEST_FQDN = "test1.test.com";
    private static final String TEST_FQDN2 = "test2.test.com";
    private static final String TEST_FRIENDLY_NAME = "friendly name";
    private static final String TEST_FRIENDLY_NAME2 = "second friendly name";
    private static final String TEST_REALM = "realm.test.com";
    private static final String TEST_REALM2 = "realm.test2.com";
    private static final String TEST_REALM3 = "realm.test3.com";
    private static final String TEST_IMSI = "123456*";
    private static final String FULL_IMSI = "123456789123456";
    private static final int TEST_CARRIER_ID = 10;
    private static final int TEST_SUBID = 1;
    private static final String TEST_VENUE_URL_ENG = "https://www.google.com/";
    private static final String TEST_VENUE_URL_HEB = "https://www.google.co.il/";
    private static final String TEST_LOCALE_ENGLISH = "eng";
    private static final String TEST_LOCALE_HEBREW = "heb";
    private static final String TEST_LOCALE_SPANISH = "spa";
    private static final String TEST_TERMS_AND_CONDITIONS_URL =
            "https://policies.google.com/terms?hl=en-US";
    private static final String TEST_TERMS_AND_CONDITIONS_URL_NON_HTTPS =
            "http://policies.google.com/terms?hl=en-US";
    private static final String TEST_TERMS_AND_CONDITIONS_URL_INVALID =
            "httpps://policies.google.com/terms?hl=en-US";

    private static final long TEST_BSSID = 0x112233445566L;
    private static final String TEST_SSID = "TestSSID";
    private static final String TEST_BSSID_STRING = "11:22:33:44:55:66";
    private static final String TEST_SSID2 = "TestSSID2";
    private static final String TEST_BSSID_STRING2 = "11:22:33:44:55:77";
    private static final String TEST_SSID3 = "TestSSID3";
    private static final String TEST_BSSID_STRING3 = "11:22:33:44:55:88";
    private static final String TEST_MCC_MNC = "123456";
    private static final String TEST_3GPP_FQDN = String.format("wlan.mnc%s.mcc%s.3gppnetwork.org",
            TEST_MCC_MNC.substring(3), TEST_MCC_MNC.substring(0, 3));

    private static final long TEST_HESSID = 0x5678L;
    private static final int TEST_ANQP_DOMAIN_ID = 0;
    private static final int TEST_ANQP_DOMAIN_ID2 = 1;
    private static final ANQPNetworkKey TEST_ANQP_KEY = ANQPNetworkKey.buildKey(
            TEST_SSID, TEST_BSSID, TEST_HESSID, TEST_ANQP_DOMAIN_ID);
    private static final ANQPNetworkKey TEST_ANQP_KEY2 = ANQPNetworkKey.buildKey(
            TEST_SSID, TEST_BSSID, TEST_HESSID, TEST_ANQP_DOMAIN_ID2);
    private static final int TEST_CREATOR_UID = 1234;
    private static final int TEST_CREATOR_UID1 = 1235;
    private static final int TEST_UID = 1500;
    private static final int TEST_NETWORK_ID = 2;
    private static final String TEST_ANONYMOUS_IDENTITY = "AnonymousIdentity";
    private static final String USER_CONNECT_CHOICE = "SomeNetworkProfileId";
    private static final int TEST_RSSI = -50;
    public static PKIXParameters TEST_PKIX_PARAMETERS;

    @Mock Context mContext;
    @Mock WifiNative mWifiNative;
    @Mock WifiKeyStore mWifiKeyStore;
    @Mock Clock mClock;
    @Mock PasspointObjectFactory mObjectFactory;
    @Mock PasspointEventHandler.Callbacks mCallbacks;
    @Mock AnqpCache mAnqpCache;
    @Mock ANQPRequestManager mAnqpRequestManager;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiConfigStore mWifiConfigStore;
    @Mock WifiSettingsStore mWifiSettingsStore;
    PasspointConfigSharedStoreData.DataSource mSharedDataSource;
    PasspointConfigUserStoreData.DataSource mUserDataSource;
    @Mock WifiMetrics mWifiMetrics;
    @Mock OsuNetworkConnection mOsuNetworkConnection;
    @Mock OsuServerConnection mOsuServerConnection;
    @Mock PasspointProvisioner mPasspointProvisioner;
    @Mock PasspointNetworkNominateHelper mPasspointNetworkNominateHelper;
    @Mock IProvisioningCallback mCallback;
    @Mock WfaKeyStore mWfaKeyStore;
    @Mock KeyStore mKeyStore;
    @Mock AppOpsManager mAppOpsManager;
    @Mock WifiInjector mWifiInjector;
    @Mock TelephonyManager mTelephonyManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    @Mock MacAddressUtil mMacAddressUtil;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock DeviceConfigFacade mDeviceConfigFacade;
    @Mock ActivityManager mActivityManager;

    RunnerHandler mHandler;
    TestLooper mLooper;
    PasspointManager mManager;
    boolean mConfigSettingsPasspointEnabled = true;
    ArgumentCaptor<AppOpsManager.OnOpChangedListener> mAppOpChangedListenerCaptor =
            ArgumentCaptor.forClass(AppOpsManager.OnOpChangedListener.class);
    WifiCarrierInfoManager mWifiCarrierInfoManager;
    ArgumentCaptor<WifiConfigManager.OnNetworkUpdateListener> mNetworkListenerCaptor =
            ArgumentCaptor.forClass(WifiConfigManager.OnNetworkUpdateListener.class);
    ArgumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener> mSubscriptionsCaptor =
            ArgumentCaptor.forClass(SubscriptionManager.OnSubscriptionsChangedListener.class);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidCAStore");
        keyStore.load(null, null);
        TEST_PKIX_PARAMETERS = new PKIXParameters(keyStore);
        TEST_PKIX_PARAMETERS.setRevocationEnabled(false);
    }

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(mWifiInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mObjectFactory.makeAnqpCache(mClock)).thenReturn(mAnqpCache);
        when(mObjectFactory.makeANQPRequestManager(any(), eq(mClock), any(), any()))
                .thenReturn(mAnqpRequestManager);
        when(mObjectFactory.makeOsuNetworkConnection(any(Context.class)))
                .thenReturn(mOsuNetworkConnection);
        when(mObjectFactory.makeOsuServerConnection())
                .thenReturn(mOsuServerConnection);
        when(mObjectFactory.makeWfaKeyStore()).thenReturn(mWfaKeyStore);
        when(mWfaKeyStore.get()).thenReturn(mKeyStore);
        when(mObjectFactory.makePasspointProvisioner(any(Context.class), any(WifiNative.class),
                any(PasspointManager.class), any(WifiMetrics.class)))
                .thenReturn(mPasspointProvisioner);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mContext.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mWifiInjector.getWifiNetworkSuggestionsManager())
                .thenReturn(mWifiNetworkSuggestionsManager);
        when(mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(anyInt()))
                .thenReturn(true);
        // Update mConfigSettingsPasspointEnabled when WifiSettingsStore#handleWifiPasspointEnabled
        // is called.
        doAnswer(invocation -> {
            mConfigSettingsPasspointEnabled = (boolean) invocation.getArguments()[0];
            // Return success
            return true;
        }).when(mWifiSettingsStore).handleWifiPasspointEnabled(anyBoolean());
        when(mWifiSettingsStore.isWifiPasspointEnabled())
                .thenReturn(mConfigSettingsPasspointEnabled);
        mLooper = new TestLooper();
        mHandler = new RunnerHandler(mLooper.getLooper(), 100, new LocalLog(128));
        mWifiCarrierInfoManager = new WifiCarrierInfoManager(mTelephonyManager,
                mSubscriptionManager, mWifiInjector, mock(FrameworkFacade.class),
                mock(WifiContext.class), mWifiConfigStore, mHandler, mWifiMetrics, mClock,
                mock(WifiPseudonymManager.class));
        verify(mSubscriptionManager).addOnSubscriptionsChangedListener(any(),
                mSubscriptionsCaptor.capture());
        mManager = new PasspointManager(mContext, mWifiInjector, mHandler, mWifiNative,
                mWifiKeyStore, mClock, mObjectFactory, mWifiConfigManager,
                mWifiConfigStore, mWifiSettingsStore, mWifiMetrics, mWifiCarrierInfoManager,
                mMacAddressUtil, mWifiPermissionsUtil);
        mManager.enableVerboseLogging(true);
        mManager.setPasspointNetworkNominateHelper(mPasspointNetworkNominateHelper);
        // Verify Passpoint is disabled on creation.
        assertFalse(mManager.isWifiPasspointEnabled());
        // send boot completed event to update enablement state.
        mManager.handleBootCompleted();
        // Verify Passpoint is enabled after getting boot completed event.
        assertTrue(mManager.isWifiPasspointEnabled());
        mManager.setUseInjectedPKIX(true);
        mManager.injectPKIXParameters(TEST_PKIX_PARAMETERS);

        ArgumentCaptor<PasspointEventHandler.Callbacks> callbacks =
                ArgumentCaptor.forClass(PasspointEventHandler.Callbacks.class);
        verify(mObjectFactory).makePasspointEventHandler(any(WifiInjector.class),
                                                         callbacks.capture());
        ArgumentCaptor<PasspointConfigSharedStoreData.DataSource> sharedDataSource =
                ArgumentCaptor.forClass(PasspointConfigSharedStoreData.DataSource.class);
        verify(mObjectFactory).makePasspointConfigSharedStoreData(sharedDataSource.capture());
        ArgumentCaptor<PasspointConfigUserStoreData.DataSource> userDataSource =
                ArgumentCaptor.forClass(PasspointConfigUserStoreData.DataSource.class);
        verify(mObjectFactory).makePasspointConfigUserStoreData(any(WifiKeyStore.class),
                any(WifiCarrierInfoManager.class), userDataSource.capture(), any(Clock.class));
        mCallbacks = callbacks.getValue();
        mSharedDataSource = sharedDataSource.getValue();
        mUserDataSource = userDataSource.getValue();
        // SIM is absent
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList())
                .thenReturn(Collections.emptyList());
        mLooper.dispatchAll();
        verify(mWifiConfigManager).addOnNetworkUpdateListener(mNetworkListenerCaptor.capture());
    }

    /**
     * Verify that the given Passpoint configuration matches the one that's added to
     * the PasspointManager.
     *
     * @param expectedConfig The expected installed Passpoint configuration
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig) {
        List<PasspointConfiguration> installedConfigs =
                mManager.getProviderConfigs(TEST_CREATOR_UID, true);
        assertEquals(1, installedConfigs.size());
        assertEquals(expectedConfig, installedConfigs.get(0));
    }

    private PasspointProvider createMockProvider(PasspointConfiguration config) {
        WifiConfiguration wifiConfig = WifiConfigurationTestUtil.generateWifiConfig(-1,
                TEST_UID, "\"PasspointTestSSID\"", true, true,
                config.getHomeSp().getFqdn(), TEST_FRIENDLY_NAME, SECURITY_EAP);
        return createMockProvider(config, wifiConfig, false);
    }

    /**
     * Create a mock PasspointProvider with default expectations.
     *
     * @param config The configuration associated with the provider
     * @return {@link com.android.server.wifi.hotspot2.PasspointProvider}
     */
    private PasspointProvider createMockProvider(
            PasspointConfiguration config, WifiConfiguration wifiConfig, boolean isSuggestion) {
        PasspointProvider provider = mock(PasspointProvider.class);
        when(provider.installCertsAndKeys()).thenReturn(true);
        lenient().when(provider.getConfig()).thenReturn(config);
        lenient().when(provider.getWifiConfig()).thenReturn(wifiConfig);
        lenient().when(provider.getCreatorUid()).thenReturn(TEST_CREATOR_UID);
        lenient().when(provider.isFromSuggestion()).thenReturn(isSuggestion);
        lenient().when(provider.isAutojoinEnabled()).thenReturn(true);
        return provider;
    }

    /**
     * Helper function for creating a test configuration with user credential.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration createTestConfigWithUserCredential(String fqdn,
            String friendlyName) {
        return createTestConfigWithUserCredentialAndRealm(fqdn, friendlyName, TEST_REALM);
    }

        /**
         * Helper function for creating a test configuration with user credential
         * and a unique realm.
         *
         * @return {@link PasspointConfiguration}
         */
    private PasspointConfiguration createTestConfigWithUserCredentialAndRealm(String fqdn,
            String friendlyName, String realm) {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm(realm != null ? realm : TEST_REALM);
        credential.setCaCertificate(FakeKeys.CA_CERT0);
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername("username");
        userCredential.setPassword("password");
        userCredential.setEapType(EAPConstants.EAP_TTLS);
        userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAP);
        credential.setUserCredential(userCredential);
        config.setCredential(credential);
        return config;
    }

    /**
     * Helper function for creating a test configuration with SIM credential.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration createTestConfigWithSimCredential(String fqdn, String imsi,
            String realm) {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(TEST_FRIENDLY_NAME);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm(TEST_REALM);
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi(imsi);
        simCredential.setEapType(EAPConstants.EAP_SIM);
        credential.setSimCredential(simCredential);
        config.setCredential(credential);
        return config;
    }

    private PasspointProvider addTestProvider(String fqdn, String friendlyName,
            String packageName, boolean isSuggestion, String realm,
            boolean addServiceFriendlyNames) {
        WifiConfiguration wifiConfig = WifiConfigurationTestUtil.generateWifiConfig(-1, TEST_UID,
                "\"PasspointTestSSID\"", true, true,
                fqdn, friendlyName, SECURITY_EAP);

        return addTestProvider(fqdn, friendlyName, packageName, wifiConfig, isSuggestion, realm,
                addServiceFriendlyNames);
    }

    /**
     * Helper function for adding a test provider to the manager.  Return the mock
     * provider that's added to the manager.
     *
     * @return {@link PasspointProvider}
     */
    private PasspointProvider addTestProvider(String fqdn, String friendlyName,
            String packageName, WifiConfiguration wifiConfig, boolean isSuggestion, String realm,
            boolean addServiceFriendlyNames) {
        PasspointConfiguration config =
                createTestConfigWithUserCredentialAndRealm(fqdn, friendlyName, realm);
        wifiConfig.setPasspointUniqueId(config.getUniqueId());
        if (addServiceFriendlyNames) {
            Map<String, String> friendlyNames = new HashMap<>();
            friendlyNames.put("en", friendlyName);
            friendlyNames.put("kr", friendlyName + 1);
            friendlyNames.put("jp", friendlyName + 2);
            config.setServiceFriendlyNames(friendlyNames);
        }
        PasspointProvider provider = createMockProvider(config, wifiConfig, isSuggestion);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(isSuggestion), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(packageName);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, isSuggestion, true, false));
        verify(mPasspointNetworkNominateHelper, atLeastOnce()).refreshWifiConfigsForProviders();
        return provider;
    }

    /**
     * Helper function for creating a ScanResult for testing.
     *
     * @return {@link ScanResult}
     */
    private ScanResult createTestScanResult() {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = TEST_SSID;
        scanResult.BSSID = TEST_BSSID_STRING;
        scanResult.hessid = TEST_HESSID;
        scanResult.anqpDomainId = TEST_ANQP_DOMAIN_ID;
        scanResult.flags = ScanResult.FLAG_PASSPOINT_NETWORK;
        return scanResult;
    }

    /**
     * Helper function for creating a ScanResult for testing.
     *
     * @return {@link ScanResult}
     */
    private List<ScanResult> createTestScanResults() {
        List<ScanResult> scanResults = new ArrayList<>();

        // Passpoint AP
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = TEST_SSID;
        scanResult.BSSID = TEST_BSSID_STRING;
        scanResult.hessid = TEST_HESSID;
        scanResult.flags = ScanResult.FLAG_PASSPOINT_NETWORK;
        scanResult.anqpDomainId = TEST_ANQP_DOMAIN_ID2;
        scanResults.add(scanResult);

        // Non-Passpoint AP
        ScanResult scanResult2 = new ScanResult();
        scanResult2.SSID = TEST_SSID2;
        scanResult2.BSSID = TEST_BSSID_STRING2;
        scanResult2.hessid = TEST_HESSID;
        scanResult2.flags = 0;
        scanResults.add(scanResult2);

        // Passpoint AP
        ScanResult scanResult3 = new ScanResult();
        scanResult3.SSID = TEST_SSID3;
        scanResult3.BSSID = TEST_BSSID_STRING3;
        scanResult3.hessid = TEST_HESSID;
        scanResult3.flags = ScanResult.FLAG_PASSPOINT_NETWORK;
        scanResult3.anqpDomainId = TEST_ANQP_DOMAIN_ID2;
        scanResults.add(scanResult3);

        return scanResults;
    }

    /**
     * Verify that the ANQP elements will be added to the ANQP cache on receiving a successful
     * response.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseSuccess() throws Exception {
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                new DomainNameElement(Arrays.asList(new String[] {"test.com"})));

        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, true)).thenReturn(TEST_ANQP_KEY);
        mCallbacks.onANQPResponse(TEST_BSSID, anqpElementMap);
        verify(mAnqpCache).addOrUpdateEntry(TEST_ANQP_KEY, anqpElementMap);
        verify(mContext, never()).sendBroadcastAsUser(any(Intent.class), any(UserHandle.class),
                any(String.class));
    }

    /**
     * Verify that no ANQP elements will be added to the ANQP cache on receiving a successful
     * response for a request that's not sent by us.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseSuccessWithUnknownRequest() throws Exception {
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                new DomainNameElement(Arrays.asList(new String[] {"test.com"})));

        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, true)).thenReturn(null);
        mCallbacks.onANQPResponse(TEST_BSSID, anqpElementMap);
        verify(mAnqpCache, never()).addOrUpdateEntry(any(ANQPNetworkKey.class), anyMap());
    }

    /**
     * Verify that no ANQP elements will be added to the ANQP cache on receiving a failure response.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseFailure() throws Exception {
        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, false)).thenReturn(TEST_ANQP_KEY);
        mCallbacks.onANQPResponse(TEST_BSSID, null);
        verify(mAnqpCache, never()).addOrUpdateEntry(any(ANQPNetworkKey.class), anyMap());

    }

    /**
     * Verify that adding a provider with a null configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithNullConfig() throws Exception {
        assertFalse(mManager.addOrUpdateProvider(null, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider with a empty configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithEmptyConfig() throws Exception {
        assertFalse(mManager.addOrUpdateProvider(new PasspointConfiguration(), TEST_CREATOR_UID,
                TEST_PACKAGE, false, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify taht adding a provider with an invalid credential will fail (using EAP-TLS
     * for user credential).
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithInvalidCredential() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        // EAP-TLS not allowed for user credential.
        config.getCredential().getUserCredential().setEapType(EAPConstants.EAP_TLS);
        assertFalse(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider from a background user will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithBackgroundUser() throws Exception {
        when(mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(anyInt()))
                .thenReturn(false);

        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertFalse(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID,
                TEST_PACKAGE, false, true, false));

        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a user saved provider with a valid configuration and user credential will
     * succeed.
     *
     * @throws Exception
     */
    private void addRemoveSavedProviderWithValidUserCredential(boolean useFqdn) throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        verify(mAppOpsManager).startWatchingMode(eq(OPSTR_CHANGE_WIFI_STATE), eq(TEST_PACKAGE),
                any(AppOpsManager.OnOpChangedListener.class));
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify content in the data source.
        List<PasspointProvider> providers = mUserDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());

        // Verify calling |enableAutoJoin|, |enableMacRandomization|, and |setMeteredOverride|
        verifyEnableAutojoin(providers.get(0), useFqdn);
        verifyEnableMacRandomization(providers.get(0));
        verifySetMeteredOverride(providers.get(0));

        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Remove the provider as the creator app.
        if (useFqdn) {
            assertTrue(mManager.removeProvider(TEST_CREATOR_UID, false, null, TEST_FQDN));
        } else {
            assertTrue(
                    mManager.removeProvider(TEST_CREATOR_UID, false, config.getUniqueId(), null));
        }

        verify(provider).uninstallCertsAndKeys();
        verify(mWifiConfigManager, times(3)).removePasspointConfiguredNetwork(
                provider.getWifiConfig().getProfileKey());
        /**
         * 1 from |removeProvider| + 2 from |setAutojoinEnabled| + 2 from
         * |enableMacRandomization| + 2 from |setMeteredOverride| = 7 calls to |saveToStore|
         */
        verify(mWifiConfigManager, times(7)).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallSuccess();
        verify(mAppOpsManager).stopWatchingMode(any(AppOpsManager.OnOpChangedListener.class));
        assertTrue(mManager.getProviderConfigs(TEST_CREATOR_UID, false).isEmpty());
        verify(mWifiConfigManager).removeConnectChoiceFromAllNetworks(config.getUniqueId());

        // Verify content in the data source.
        assertTrue(mUserDataSource.getProviders().isEmpty());
        // Removing a provider should not change the provider index.
        assertEquals(1, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a user saved provider with a valid configuration and user credential will
     * succeed. Remove provider using FQDN as key.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveByFqdnSavedProviderWithValidUserCredential() throws Exception {
        addRemoveSavedProviderWithValidUserCredential(true);
    }

    /**
     * Verify that adding a user saved provider with a valid configuration and user credential will
     * succeed. Remove provider using unique identifier as key.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveByUniqueIdSavedProviderWithValidUserCredential() throws Exception {
        addRemoveSavedProviderWithValidUserCredential(false);
    }

    /**
     * Verify enable/disable autojoin on a provider.
     * @param provider a mock provider that is already added into the PasspointManager
     */
    private void verifyEnableAutojoin(PasspointProvider provider, boolean useFqdn) {
        when(provider.setAutojoinEnabled(anyBoolean())).thenReturn(true);
        if (useFqdn) {
            assertTrue(mManager.enableAutojoin(null, provider.getConfig().getHomeSp().getFqdn(),
                    false));
            verify(provider).setAutojoinEnabled(false);
            assertTrue(mManager.enableAutojoin(null, provider.getConfig().getHomeSp().getFqdn(),
                    true));
            verify(provider).setAutojoinEnabled(true);
            assertFalse(mManager.enableAutojoin(null, provider.getConfig().getHomeSp()
                    .getFqdn() + "-XXXX", true));
        } else {
            assertTrue(mManager.enableAutojoin(provider.getConfig().getUniqueId(), null,
                    false));
            verify(provider).setAutojoinEnabled(false);
            assertTrue(mManager.enableAutojoin(provider.getConfig().getUniqueId(), null,
                    true));
            verify(provider).setAutojoinEnabled(true);
            assertFalse(
                    mManager.enableAutojoin(provider.getConfig().getHomeSp().getFqdn() + "-XXXX",
                            null, true));
        }
        verify(mWifiMetrics).logUserActionEvent(UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_OFF,
                false, true);
        verify(mWifiMetrics).logUserActionEvent(UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON,
                false, true);
    }

    /**
     * Verify enable/disable mac randomization on a provider.
     * @param provider a mock provider that is already added into the PasspointManager
     */
    private void verifyEnableMacRandomization(PasspointProvider provider) {
        when(provider.setMacRandomizationEnabled(anyBoolean())).thenReturn(true);
        assertTrue(mManager.enableMacRandomization(provider.getConfig().getHomeSp().getFqdn(),
                false));
        verify(provider).setMacRandomizationEnabled(false);
        verify(mWifiMetrics).logUserActionEvent(
                UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_OFF, false, true);
        assertTrue(mManager.enableMacRandomization(provider.getConfig().getHomeSp().getFqdn(),
                true));
        verify(mWifiConfigManager, times(2)).removePasspointConfiguredNetwork(
                provider.getWifiConfig().getProfileKey());
        verify(mWifiMetrics).logUserActionEvent(
                UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_ON, false, true);
        verify(provider).setMacRandomizationEnabled(true);
        assertFalse(mManager.enableMacRandomization(provider.getConfig().getHomeSp().getFqdn()
                + "-XXXX", false));
    }

    private void verifySetMeteredOverride(PasspointProvider provider) {
        when(provider.setMeteredOverride(anyInt())).thenReturn(true);
        assertTrue(mManager.setMeteredOverride(provider.getConfig().getHomeSp().getFqdn(),
                METERED_OVERRIDE_METERED));
        verify(provider).setMeteredOverride(METERED_OVERRIDE_METERED);
        verify(mWifiMetrics).logUserActionEvent(
                UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_METERED, false, true);
        assertTrue(mManager.setMeteredOverride(provider.getConfig().getHomeSp().getFqdn(),
                METERED_OVERRIDE_NOT_METERED));
        verify(provider).setMeteredOverride(METERED_OVERRIDE_NOT_METERED);
        verify(mWifiMetrics).logUserActionEvent(
                UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_UNMETERED, false, true);
        assertFalse(mManager.setMeteredOverride(provider.getConfig().getHomeSp().getFqdn()
                + "-XXXX", METERED_OVERRIDE_METERED));
    }

    /**
     * Verify that adding a user saved  provider with a valid configuration and SIM credential will
     * succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveSavedProviderWithValidSimCredential() throws Exception {
        PasspointConfiguration config = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify content in the data source.
        List<PasspointProvider> providers = mUserDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());
        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Remove the provider as a privileged non-creator app.
        assertTrue(mManager.removeProvider(TEST_UID, true, null, TEST_FQDN));
        verify(provider).uninstallCertsAndKeys();
        verify(mWifiConfigManager).removePasspointConfiguredNetwork(
                provider.getWifiConfig().getProfileKey());
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallSuccess();
        assertTrue(mManager.getProviderConfigs(TEST_UID, true).isEmpty());
        verify(mWifiConfigManager).removeConnectChoiceFromAllNetworks(config.getUniqueId());

        // Verify content in the data source.
        assertTrue(mUserDataSource.getProviders().isEmpty());
        // Removing a provider should not change the provider index.
        assertEquals(1, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that if the passpoint profile has full IMSI, the carrier ID should be updated when
     * the matched SIM card is present.
     * @throws Exception
     */
    @Test
    public void addProviderWithValidFullImsiOfSimCredential() throws Exception {
        PasspointConfiguration config =
                createTestConfigWithSimCredential(TEST_FQDN, FULL_IMSI, TEST_REALM);
        X509Certificate[] certArr = new X509Certificate[] {FakeKeys.CA_CERT0};
        config.getCredential().setCaCertificates(certArr);
        SubscriptionInfo subInfo = mock(SubscriptionInfo.class);
        when(subInfo.getSubscriptionId()).thenReturn(TEST_SUBID);
        when(subInfo.getCarrierId()).thenReturn(TEST_CARRIER_ID);
        TelephonyManager specifiedTm = mock(TelephonyManager.class);
        when(mTelephonyManager.createForSubscriptionId(eq(TEST_SUBID))).thenReturn(specifiedTm);
        when(specifiedTm.getSubscriberId()).thenReturn(FULL_IMSI);
        when(specifiedTm.getSimApplicationState()).thenReturn(TelephonyManager.SIM_STATE_LOADED);
        List<SubscriptionInfo> subInfoList = List.of(subInfo);
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenReturn(subInfoList);
        mSubscriptionsCaptor.getValue().onSubscriptionsChanged();
        when(mWifiKeyStore.putCaCertInKeyStore(any(String.class), any(Certificate.class)))
                .thenReturn(true);
        PasspointObjectFactory spyFactory = spy(new PasspointObjectFactory());
        when(mWifiNetworkSuggestionsManager.isPasspointSuggestionSharedWithUser(any()))
                .thenReturn(true);
        PasspointManager ut = new PasspointManager(mContext, mWifiInjector, mHandler, mWifiNative,
                mWifiKeyStore, mClock, spyFactory, mWifiConfigManager,
                mWifiConfigStore, mWifiSettingsStore, mWifiMetrics, mWifiCarrierInfoManager,
                mMacAddressUtil, mWifiPermissionsUtil);

        assertTrue(ut.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));

        assertEquals(TEST_CARRIER_ID, config.getCarrierId());
    }

    /**
     * Verify that adding a user saved provider with the same base domain as the existing provider
     * will succeed, and verify that the new provider with the new configuration is added.
     *
     * @throws Exception
     */
    @Test
    public void addSavedProviderWithExistingConfig() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider origProvider = createMockProvider(origConfig);
        when(mObjectFactory.makePasspointProvider(eq(origConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(origProvider);
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(origConfig);
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        verify(origProvider, never()).setUserConnectChoice(any(), anyInt());
        verify(origProvider, never()).setAutojoinEnabled(anyBoolean());
        verify(origProvider, never()).setAnonymousIdentity(any());
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify data source content.
        List<PasspointProvider> origProviders = mUserDataSource.getProviders();
        assertEquals(1, origProviders.size());
        assertEquals(origConfig, origProviders.get(0).getConfig());
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Add same provider as existing suggestion provider
        // This should be no WifiConfig deletion
        WifiConfiguration origWifiConfig = origProvider.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(origWifiConfig.getProfileKey()))
                .thenReturn(origWifiConfig);
        when(mWifiConfigManager.addOrUpdateNetwork(
                origWifiConfig, TEST_CREATOR_UID, TEST_PACKAGE, false))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(origProvider.getAnonymousIdentity()).thenReturn(TEST_ANONYMOUS_IDENTITY);
        when(origProvider.getConnectChoice()).thenReturn(USER_CONNECT_CHOICE);
        when(origProvider.getConnectChoiceRssi()).thenReturn(TEST_RSSI);
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verify(mWifiConfigManager, never()).removePasspointConfiguredNetwork(
                origWifiConfig.getProfileKey());
        verify(mWifiConfigManager).addOrUpdateNetwork(
                argThat((c) -> c.FQDN.equals(TEST_FQDN)), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false));
        verify(mWifiConfigManager).allowAutojoin(TEST_NETWORK_ID, origWifiConfig.allowAutojoin);
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        assertEquals(2, mSharedDataSource.getProviderIndex());
        // Update provider will keep the user settings from the existing provider.
        verify(origProvider).setUserConnectChoice(eq(USER_CONNECT_CHOICE), eq(TEST_RSSI));
        verify(origProvider).setAnonymousIdentity(eq(TEST_ANONYMOUS_IDENTITY));
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Add another provider with the same base domain as the existing provider.
        // This should replace the existing provider with the new configuration.
        PasspointConfiguration newConfig = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider newProvider = createMockProvider(newConfig);
        when(mObjectFactory.makePasspointProvider(eq(newConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(newProvider);
        when(mWifiConfigManager.getConfiguredNetwork(origProvider.getWifiConfig()
                .getProfileKey())).thenReturn(origWifiConfig);
        assertTrue(mManager.addOrUpdateProvider(newConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));

        List<PasspointConfiguration> installedConfigs =
                mManager.getProviderConfigs(TEST_CREATOR_UID, true);
        assertEquals(2, installedConfigs.size());
        assertTrue(installedConfigs.contains(origConfig));
        assertTrue(installedConfigs.contains(newConfig));

        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();

        // Verify data source content.
        List<PasspointProvider> newProviders = mUserDataSource.getProviders();
        assertEquals(2, newProviders.size());
        assertTrue(newConfig.equals(newProviders.get(0).getConfig())
                || newConfig.equals(newProviders.get(1).getConfig()));
        assertEquals(3, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a provider will fail when failing to install certificates and
     * key to the keystore.
     *
     * @throws Exception
     */
    @Test
    public void addProviderOnKeyInstallationFailiure() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = mock(PasspointProvider.class);
        when(provider.installCertsAndKeys()).thenReturn(false);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore), eq(
                mWifiCarrierInfoManager),
                anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE), eq(false),
                eq(mClock))).thenReturn(provider);
        assertFalse(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider with R1 configuration and a private self-signed CA certificate
     * is installed correctly.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithR1ConfigPrivateCaCert() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider with R2 configuration will not perform CA certificate
     * verification.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithR2Config() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        config.setUpdateIdentifier(1);
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that removing a non-existing provider will fail.
     *
     * @throws Exception
     */
    @Test
    public void removeNonExistingProvider() throws Exception {
        assertFalse(mManager.removeProvider(TEST_CREATOR_UID, true, null, TEST_FQDN));
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderUninstallSuccess();
    }

    /**
     * Verify that a empty list will be returned when no providers are installed.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithNoProvidersInstalled() throws Exception {
        assertTrue(mManager.matchProvider(createTestScanResult()).isEmpty());
    }

    /**
     * Verify that a {code null} be returned when ANQP entry doesn't exist in the cache.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithAnqpCacheMissed() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);

            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.hsRelease = NetworkDetail.HSRelease.R1;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            InformationElementUtil.RoamingConsortium roamingConsortium =
                    new InformationElementUtil.RoamingConsortium();
            roamingConsortium.anqpOICount = 0;
            when(InformationElementUtil.getRoamingConsortiumIE(isNull()))
                    .thenReturn(roamingConsortium);
            assertTrue(mManager.matchProvider(createTestScanResult()).isEmpty());
            // Verify that a request for ANQP elements is initiated.
            verify(mAnqpRequestManager).requestANQPElements(eq(TEST_BSSID),
                    any(ANQPNetworkKey.class),
                    anyBoolean(), any(NetworkDetail.HSRelease.class));
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that the expected provider will be returned when a HomeProvider is matched.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderAsHomeProvider() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        ANQPData entry = new ANQPData(mClock, null);

        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        when(provider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
            .thenReturn(PasspointMatch.HomeProvider);
        List<Pair<PasspointProvider, PasspointMatch>> results =
                mManager.matchProvider(createTestScanResult());
        Pair<PasspointProvider, PasspointMatch> result = results.get(0);
        assertEquals(PasspointMatch.HomeProvider, result.second);
        assertEquals(TEST_FQDN, result.first.getConfig().getHomeSp().getFqdn());
    }

    /**
     * Verify that the expected provider will be returned when a RoamingProvider is matched.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderAsRoamingProvider() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        ANQPData entry = new ANQPData(mClock, null);

        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        when(provider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
            .thenReturn(PasspointMatch.RoamingProvider);
        List<Pair<PasspointProvider, PasspointMatch>> results =
                mManager.matchProvider(createTestScanResult());
        Pair<PasspointProvider, PasspointMatch> result = results.get(0);
        assertEquals(PasspointMatch.RoamingProvider, result.second);
        assertEquals(TEST_FQDN, result.first.getConfig().getHomeSp().getFqdn());
    }

    /**
     * Verify that a {code null} will be returned when there is no matching provider.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithNoMatch() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        ANQPData entry = new ANQPData(mClock, null);

        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        when(provider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
            .thenReturn(PasspointMatch.None);
        assertTrue(mManager.matchProvider(createTestScanResult()).isEmpty());
    }

    /**
     * Verify the expectations for sweepCache.
     *
     * @throws Exception
     */
    @Test
    public void sweepCache() throws Exception {
        mManager.sweepCache();
        verify(mAnqpCache).sweep();
    }

    /**
     * Verify that an empty map will be returned if ANQP elements are not cached for the given AP.
     *
     * @throws Exception
     */
    @Test
    public void getANQPElementsWithNoMatchFound() throws Exception {
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);
        assertTrue(mManager.getANQPElements(createTestScanResult()).isEmpty());
    }

    /**
     * Verify that an expected ANQP elements will be returned if ANQP elements are cached for the
     * given AP.
     *
     * @throws Exception
     */
    @Test
    public void getANQPElementsWithMatchFound() throws Exception {
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                new DomainNameElement(Arrays.asList(new String[] {"test.com"})));
        ANQPData entry = new ANQPData(mClock, anqpElementMap);

        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        assertEquals(anqpElementMap, mManager.getANQPElements(createTestScanResult()));
    }

    /**
     * Verify that if the Carrier ID is updated during match, the config should be persisted.
     */
    @Test
    public void getAllMatchingProvidersUpdatedConfigWithFullImsiSimCredential() {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            PasspointProvider provider = addTestProvider(TEST_FQDN + 0, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            when(provider.tryUpdateCarrierId()).thenReturn(true);
            reset(mWifiConfigManager);

            ANQPData entry = new ANQPData(mClock, null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID2;

            when(mAnqpCache.getEntry(TEST_ANQP_KEY2)).thenReturn(entry);
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            when(provider.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.HomeProvider);

            List<Pair<PasspointProvider, PasspointMatch>> matchedProviders =
                    mManager.getAllMatchedProviders(createTestScanResult());

            verify(mWifiConfigManager).saveToStore();

        } finally {
            session.finishMocking();
        }
    }
    /**
     * Verify that an expected map of FQDN and a list of ScanResult will be returned when provided
     * scanResults are matched to installed Passpoint profiles.
     */
    @Test
    public void getAllMatchingFqdnsForScanResults() {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            PasspointProvider providerHome = addTestProvider(TEST_FQDN + 0, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            providerHome.getWifiConfig().isHomeProviderNetwork = true;
            PasspointProvider providerRoaming = addTestProvider(TEST_FQDN + 1, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.generateWifiConfig(-1,
                    TEST_UID, "\"PasspointTestSSID\"", true, true,
                    TEST_FQDN + 2, TEST_FRIENDLY_NAME, SECURITY_EAP);
            PasspointProvider providerNone = addTestProvider(TEST_FQDN + 2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, wifiConfiguration, false, null, false);
            ANQPData entry = new ANQPData(mClock, null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID2;

            when(mAnqpCache.getEntry(TEST_ANQP_KEY2)).thenReturn(entry);
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            when(providerHome.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.HomeProvider);
            when(providerRoaming.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.RoamingProvider);
            when(providerNone.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.None);

            Map<String, Map<Integer, List<ScanResult>>> configs =
                    mManager.getAllMatchingPasspointProfilesForScanResults(
                            createTestScanResults());

            // Expects to be matched with home Provider for each AP (two APs).
            assertEquals(2, configs.get(providerHome.getConfig().getUniqueId()).get(
                    WifiManager.PASSPOINT_HOME_NETWORK).size());
            assertFalse(configs.get(providerHome.getConfig().getUniqueId())
                            .containsKey(WifiManager.PASSPOINT_ROAMING_NETWORK));

            // Expects to be matched with roaming Provider for each AP (two APs).
            assertEquals(2, configs.get(providerRoaming.getConfig().getUniqueId()).get(
                    WifiManager.PASSPOINT_ROAMING_NETWORK).size());
            assertFalse(configs.get(providerRoaming.getConfig().getUniqueId())
                    .containsKey(WifiManager.PASSPOINT_HOME_NETWORK));

        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that an expected list of {@link WifiConfiguration} will be returned when provided
     * a list of FQDN is matched to installed Passpoint profiles which is already added into the
     * WifiConfigManager. For suggestion passpoint network, will check if that suggestion share
     * credential with user to choose from wifi picker.
     * - Provider1 and Provider2 are saved passpoint, Provider1 is already added into the
     * WifiConfigManger
     * - Provider3 and Provider4 are suggestion passpoint, only Provider4 is shared with user. Both
     * providers are already added into the WifiConfigManager
     * - Expected result: Provider1 and Provider4 should be returned .
     */
    @Test
    public void getWifiConfigsForPasspointProfiles() {
        PasspointProvider provider1 = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        WifiConfiguration config1 = provider1.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(provider1.getConfig().getUniqueId()))
                .thenReturn(config1);
        PasspointProvider provider2 = addTestProvider(TEST_FQDN + 1, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        PasspointProvider provider3 = addTestProvider(TEST_FQDN + 2, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, true, null, false);
        when(mWifiNetworkSuggestionsManager
                .isPasspointSuggestionSharedWithUser(provider3.getWifiConfig())).thenReturn(false);
        WifiConfiguration config3 = provider3.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(provider3.getConfig().getUniqueId()))
                .thenReturn(config3);
        PasspointProvider provider4 = addTestProvider(TEST_FQDN + 3, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, true, null, false);
        when(mWifiNetworkSuggestionsManager
                .isPasspointSuggestionSharedWithUser(provider4.getWifiConfig())).thenReturn(true);
        WifiConfiguration config4 = provider4.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(provider4.getConfig().getUniqueId()))
                .thenReturn(config4);
        verify(mPasspointNetworkNominateHelper, times(4)).refreshWifiConfigsForProviders();
        reset(mPasspointNetworkNominateHelper);
        List<WifiConfiguration> wifiConfigurationList = mManager.getWifiConfigsForPasspointProfiles(
                List.of(provider1.getConfig().getUniqueId(), provider2.getConfig().getUniqueId(),
                        provider3.getConfig().getUniqueId(), provider4.getConfig().getUniqueId(),
                        TEST_FQDN + "_353ab8c93", TEST_FQDN + "_83765319aca"));
        verify(mPasspointNetworkNominateHelper).refreshWifiConfigsForProviders();
        assertEquals(2, wifiConfigurationList.size());
        Set<String> uniqueIdSet = wifiConfigurationList
                .stream()
                .map(WifiConfiguration::getPasspointUniqueId)
                .collect(Collectors.toSet());
        assertTrue(uniqueIdSet.contains(provider1.getConfig().getUniqueId()));
        assertTrue(uniqueIdSet.contains(provider4.getConfig().getUniqueId()));
    }

    /**
     * Verify that a {@link WifiConfiguration} will be returned with the correct value for the
     * randomized MAC address.
     */
    @Test
    public void getWifiConfigsForPasspointProfilesWithoutNonPersistentMacRandomization() {
        MacAddress randomizedMacAddress = MacAddress.fromString("01:23:45:67:89:ab");
        when(mMacAddressUtil.calculatePersistentMacForSta(any(), anyInt()))
                .thenReturn(randomizedMacAddress);
        when(mWifiConfigManager.shouldUseNonPersistentRandomization(any())).thenReturn(false);
        PasspointProvider provider = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        WifiConfiguration configuration = provider.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(provider.getConfig().getUniqueId()))
                .thenReturn(configuration);
        WifiConfiguration config = mManager.getWifiConfigsForPasspointProfiles(
                Collections.singletonList(provider.getConfig().getUniqueId())).get(0);
        assertEquals(config.getRandomizedMacAddress(), randomizedMacAddress);
        verify(mMacAddressUtil).calculatePersistentMacForSta(
                eq(provider.getConfig().getUniqueId()), anyInt());
    }

    /**
     * Verify that a {@link WifiConfiguration} will be returned with DEFAULT_MAC_ADDRESS for the
     * randomized MAC address if non-persistent mac randomization is enabled. This value will
     * display in the wifi picker's network details page as "Not available" if the network is
     * disconnected.
     */
    @Test
    public void getWifiConfigsForPasspointProfilesWithNonPersistentMacRandomization() {
        MacAddress randomizedMacAddress = MacAddress.fromString("01:23:45:67:89:ab");
        when(mMacAddressUtil.calculatePersistentMacForSta(any(), anyInt()))
                .thenReturn(randomizedMacAddress);
        when(mWifiConfigManager.shouldUseNonPersistentRandomization(any())).thenReturn(true);
        PasspointProvider provider = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        WifiConfiguration configuration = provider.getWifiConfig();
        when(mWifiConfigManager.getConfiguredNetwork(provider.getConfig().getUniqueId()))
                .thenReturn(configuration);
        WifiConfiguration config = mManager.getWifiConfigsForPasspointProfiles(
                Collections.singletonList(provider.getConfig().getUniqueId())).get(0);
        assertEquals(config.getRandomizedMacAddress(), MacAddress.fromString(DEFAULT_MAC_ADDRESS));
    }

    /**
     * Verify that {@link PasspointManager#getWifiConfigsForPasspointProfiles(boolean)} returns
     * configs for the expected providers.
     */
    @Test
    public void testGetWifiConfigsForPasspointProfilesWithSsids() {
        PasspointProvider provider1 = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        PasspointProvider provider2 = addTestProvider(TEST_FQDN + 1, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, null, false);
        when(provider2.getMostRecentSsid()).thenReturn(TEST_SSID); // assign a recent SSID

        // If SSIDs are not required, both providers should appear in the results list.
        List<WifiConfiguration> configs = mManager.getWifiConfigsForPasspointProfiles(false);
        assertEquals(2, configs.size());

        // If SSIDs are required, only the provider with an SSID should appear in the results.
        configs = mManager.getWifiConfigsForPasspointProfiles(true);
        assertEquals(1, configs.size());
        assertEquals(provider2.getConfig().getUniqueId(), configs.get(0).getPasspointUniqueId());
        assertEquals(TEST_SSID, configs.get(0).SSID);
        assertTrue(configs.get(0).getNetworkSelectionStatus().hasEverConnected());
    }

    /**
     * Verify that an empty map will be returned when trying to get all matching FQDN for a {@code
     * null} {@link ScanResult}.
     */
    @Test
    public void getAllMatchingFqdnsForScanResultsWithNullScanResult() throws Exception {
        assertEquals(0,
                mManager.getAllMatchingPasspointProfilesForScanResults(null).size());
    }

    /**
     * Verify that an empty map will be returned when trying to get a all matching FQDN for a {@link
     * ScanResult} with a {@code null} BSSID.
     */
    @Test
    public void getAllMatchingFqdnsForScanResultsWithNullBSSID() throws Exception {
        ScanResult scanResult = createTestScanResult();
        scanResult.BSSID = null;

        assertEquals(0,
                mManager.getAllMatchingPasspointProfilesForScanResults(
                        Arrays.asList(scanResult)).size());
    }

    /**
     * Verify that an empty map will be returned when trying to get all matching FQDN for a {@link
     * ScanResult} with an invalid BSSID.
     */
    @Test
    public void ggetAllMatchingFqdnsForScanResultsWithInvalidBSSID() throws Exception {
        ScanResult scanResult = createTestScanResult();
        scanResult.BSSID = "asdfdasfas";

        assertEquals(0,
                mManager.getAllMatchingPasspointProfilesForScanResults(
                        Arrays.asList(scanResult)).size());
    }

    /**
     * Verify that an empty map will be returned when trying to get all matching FQDN for a
     * non-Passpoint AP.
     */
    @Test
    public void getAllMatchingFqdnsForScanResultsForNonPasspointAP() throws Exception {
        ScanResult scanResult = createTestScanResult();
        scanResult.flags = 0;
        assertEquals(0,
                mManager.getAllMatchingPasspointProfilesForScanResults(
                        Arrays.asList(scanResult)).size());
    }

    /**
     * Verify that an empty list will be returned when retrieving OSU providers for an AP with
     * null scan result.
     *
     * @throws Exception
     */
    @Test
    public void getMatchingOsuProvidersForNullScanResult() throws Exception {
        assertTrue(mManager.getMatchingOsuProviders(null).isEmpty());
    }

    /**
     * Verify that an empty list will be returned when retrieving OSU providers for an AP with
     * invalid BSSID.
     *
     * @throws Exception
     */
    @Test
    public void getMatchingOsuProvidersForInvalidBSSID() throws Exception {
        ScanResult scanResult = createTestScanResult();
        scanResult.BSSID = "asdfdasfas";
        assertTrue(mManager.getMatchingOsuProviders(Arrays.asList(scanResult)).isEmpty());
    }

    /**
     * Verify that an empty list will be returned when retrieving OSU providers for a
     * non-Passpoint AP.
     *
     * @throws Exception
     */
    @Test
    public void getMatchingOsuProvidersForNonPasspointAP() throws Exception {
        ScanResult scanResult = createTestScanResult();
        scanResult.flags = 0;
        assertTrue(mManager.getMatchingOsuProviders(Arrays.asList(scanResult)).isEmpty());
    }

    /**
     * Verify that an empty list will be returned when no match is found from the ANQP cache.
     *
     * @throws Exception
     */
    @Test
    public void getMatchingOsuProviderWithNoMatch() throws Exception {
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);
        assertTrue(
                mManager.getMatchingOsuProviders(Arrays.asList(createTestScanResult())).isEmpty());
    }

    /**
     * Verify that an expected provider list will be returned when a match is found from
     * the ANQP cache with a given list of scanResult.
     *
     * @throws Exception
     */
    @Test
    public void getMatchingOsuProvidersWithMatch() throws Exception {
        // Setup OSU providers ANQP element for AP1.
        List<OsuProviderInfo> providerInfoListOfAp1 = new ArrayList<>();
        Map<ANQPElementType, ANQPElement> anqpElementMapOfAp1 = new HashMap<>();
        Set<OsuProvider> expectedOsuProvidersForDomainId = new HashSet<>();

        // Setup OSU providers ANQP element for AP2.
        List<OsuProviderInfo> providerInfoListOfAp2 = new ArrayList<>();
        Map<ANQPElementType, ANQPElement> anqpElementMapOfAp2 = new HashMap<>();
        Set<OsuProvider> expectedOsuProvidersForDomainId2 = new HashSet<>();
        int osuProviderCount = 4;

        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            for (int i = 0; i < osuProviderCount; i++) {
                // Test data.
                String friendlyName = "Test Provider" + i;
                String serviceDescription = "Dummy Service" + i;
                Uri serverUri = Uri.parse("https://" + "test" + i + ".com");
                String nai = "access.test.com";
                List<Integer> methodList = Arrays.asList(1);
                List<I18Name> friendlyNames = Arrays.asList(
                        new I18Name(Locale.ENGLISH.getLanguage(), Locale.ENGLISH, friendlyName));
                List<I18Name> serviceDescriptions = Arrays.asList(
                        new I18Name(Locale.ENGLISH.getLanguage(), Locale.ENGLISH,
                                serviceDescription));
                Map<String, String> friendlyNameMap = new HashMap<>();
                friendlyNames.forEach(e -> friendlyNameMap.put(e.getLanguage(), e.getText()));

                expectedOsuProvidersForDomainId.add(new OsuProvider(
                        (WifiSsid) null, friendlyNameMap, serviceDescription,
                        serverUri, nai, methodList));

                // add All OSU Providers for AP1.
                providerInfoListOfAp1.add(new OsuProviderInfo(
                        friendlyNames, serverUri, methodList, null, nai, serviceDescriptions));

                // add only half of All OSU Providers for AP2.
                if (i >= osuProviderCount / 2) {
                    providerInfoListOfAp2.add(new OsuProviderInfo(
                            friendlyNames, serverUri, methodList, null, nai, serviceDescriptions));
                    expectedOsuProvidersForDomainId2.add(new OsuProvider(
                            (WifiSsid) null, friendlyNameMap, serviceDescription,
                            serverUri, nai, methodList));
                }
            }
            anqpElementMapOfAp1.put(ANQPElementType.HSOSUProviders,
                    new HSOsuProvidersElement(WifiSsid.fromUtf8Text("Test SSID"),
                            providerInfoListOfAp1));
            ANQPData anqpData = new ANQPData(mClock, anqpElementMapOfAp1);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(anqpData);

            anqpElementMapOfAp2.put(ANQPElementType.HSOSUProviders,
                    new HSOsuProvidersElement(WifiSsid.fromUtf8Text("Test SSID2"),
                            providerInfoListOfAp2));
            ANQPData anqpData2 = new ANQPData(mClock, anqpElementMapOfAp2);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY2)).thenReturn(anqpData2);

            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();

            // ANQP_DOMAIN_ID(TEST_ANQP_KEY)
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            assertEquals(mManager.getMatchingOsuProviders(
                    Arrays.asList(createTestScanResult())).keySet(),
                    expectedOsuProvidersForDomainId);

            // ANQP_DOMAIN_ID2(TEST_ANQP_KEY2)
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID2;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            assertEquals(mManager.getMatchingOsuProviders(
                    createTestScanResults()).keySet(), expectedOsuProvidersForDomainId2);
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that matching Passpoint configurations will be returned as map with corresponding
     * OSU providers.
     */
    @Test
    public void getMatchingPasspointConfigsForOsuProvidersWithMatch() {
        PasspointProvider provider1 =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, true);
        PasspointProvider provider2 =
                addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME2, TEST_PACKAGE, false, null, true);

        List<OsuProvider> osuProviders = new ArrayList<>();
        Map<String, String> friendlyNames = new HashMap<>();
        friendlyNames.put("en", "NO-MATCH-NAME");
        friendlyNames.put("kr", TEST_FRIENDLY_NAME + 1);

        osuProviders.add(PasspointProvisioningTestUtil.generateOsuProviderWithFriendlyName(true,
                friendlyNames));
        friendlyNames = new HashMap<>();
        friendlyNames.put("en", TEST_FRIENDLY_NAME2);
        osuProviders.add(PasspointProvisioningTestUtil.generateOsuProviderWithFriendlyName(true,
                friendlyNames));

        Map<OsuProvider, PasspointConfiguration> results =
                mManager.getMatchingPasspointConfigsForOsuProviders(osuProviders);

        assertEquals(2, results.size());
        assertThat(Arrays.asList(provider1.getConfig(), provider2.getConfig()),
                containsInAnyOrder(results.values().toArray()));
    }

    /**
     * Verify that empty map will be returned when there is no matching Passpoint configuration.
     */
    @Test
    public void getMatchingPasspointConfigsForOsuProvidersWitNoMatch() {
        addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME2, TEST_PACKAGE, false, null, false);

        List<OsuProvider> osuProviders = new ArrayList<>();

        Map<String, String> friendlyNames = new HashMap<>();
        friendlyNames.put("en", "NO-MATCH-NAME");
        osuProviders.add(PasspointProvisioningTestUtil.generateOsuProviderWithFriendlyName(true,
                friendlyNames));
        friendlyNames = new HashMap<>();
        friendlyNames.put("en", "NO-MATCH-NAME-2");
        osuProviders.add(PasspointProvisioningTestUtil.generateOsuProviderWithFriendlyName(true,
                friendlyNames));

        assertEquals(0, mManager.getMatchingPasspointConfigsForOsuProviders(osuProviders).size());
    }

    /**
     * Verify that the provider list maintained by the PasspointManager after the list is updated
     * in the data source.
     *
     * @throws Exception
     */
    @Test
    public void verifyProvidersAfterDataSourceUpdate() throws Exception {
        // Update the provider list in the data source.
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        List<PasspointProvider> providers = new ArrayList<>();
        providers.add(provider);
        mUserDataSource.setProviders(providers);

        // Verify the providers maintained by PasspointManager.
        assertEquals(1, mManager.getProviderConfigs(TEST_CREATOR_UID, true).size());
        assertEquals(config, mManager.getProviderConfigs(TEST_CREATOR_UID, true).get(0));
    }

    /**
     * Verify that the provider index used by PasspointManager is updated after it is updated in
     * the data source.
     *
     * @throws Exception
     */
    @Test
    public void verifyProviderIndexAfterDataSourceUpdate() throws Exception {
        long providerIndex = 9;
        mSharedDataSource.setProviderIndex(providerIndex);
        assertEquals(providerIndex, mSharedDataSource.getProviderIndex());

        // Add a provider.
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        // Verify the provider ID used to create the new provider.
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), eq(providerIndex), eq(TEST_CREATOR_UID),
                eq(TEST_PACKAGE), eq(false), eq(mClock))).thenReturn(provider);

        assertTrue(
                mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE, false, true,
                        false));
        verifyInstalledConfig(config);
        reset(mWifiConfigManager);
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid user credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithUserCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String username = "username";
        String password = "password";
        byte[] base64EncodedPw =
                Base64.encode(password.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        String encodedPasswordStr = new String(base64EncodedPw, StandardCharsets.UTF_8);
        String caCertificateAlias = "CaCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setIdentity(username);
        wifiConfig.enterpriseConfig.setPassword(password);
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        wifiConfig.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername(username);
        userCredential.setPassword(encodedPasswordStr);
        userCredential.setEapType(EAPConstants.EAP_TTLS);
        userCredential.setNonEapInnerMethod("PAP");
        credential.setUserCredential(userCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing user credential will
     * fail when client certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithUserCredentialWithoutCaCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String username = "username";
        String password = "password";
        byte[] base64EncodedPw =
                Base64.encode(password.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        String encodedPasswordStr = new String(base64EncodedPw, StandardCharsets.UTF_8);

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setIdentity(username);
        wifiConfig.enterpriseConfig.setPassword(password);
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        wifiConfig.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithSimCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String imsi = "1234";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        wifiConfig.enterpriseConfig.setPlmn(imsi);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setEapType(EAPConstants.EAP_SIM);
        simCredential.setImsi(imsi);
        credential.setSimCredential(simCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String caCertificateAlias = "CaCert";
        String clientCertificateAlias = "ClientCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);
        wifiConfig.enterpriseConfig.setClientCertificateAlias(clientCertificateAlias);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        certCredential.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
        credential.setCertCredential(certCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing certificate credential will
     * fail when CA certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredentialWithoutCaCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String clientCertificateAlias = "ClientCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setClientCertificateAlias(clientCertificateAlias);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing certificate credential will
     * fail when client certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredentialWithoutClientCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String caCertificateAlias = "CaCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }

    /**
     * Verify that the provider's "hasEverConnected" flag will be set to true and the associated
     * metric is updated after the provider was used to successfully connect to a Passpoint
     * network for the first time.
     *
     * @throws Exception
     */
    @Test
    public void providerNetworkConnectedFirstTime() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        when(provider.getHasEverConnected()).thenReturn(false);
        mManager.onPasspointNetworkConnected(provider.getConfig().getUniqueId(), TEST_SSID);
        verify(provider).setHasEverConnected(eq(true));
        verify(provider).setMostRecentSsid(eq(TEST_SSID));
    }

    /**
     * Verify that the provider's "hasEverConnected" flag the associated metric is not updated
     * after the provider was used to successfully connect to a Passpoint network for non-first
     * time.
     *
     * @throws Exception
     */
    @Test
    public void providerNetworkConnectedNotFirstTime() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        when(provider.getHasEverConnected()).thenReturn(true);
        mManager.onPasspointNetworkConnected(TEST_FQDN, TEST_SSID);
        verify(provider, never()).setHasEverConnected(anyBoolean());
    }

    /**
     * Verify that the expected Passpoint metrics are updated when
     * {@link PasspointManager#updateMetrics} is invoked.
     *
     * @throws Exception
     */
    @Test
    public void updateMetrics() {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        ArgumentCaptor<Map<String, PasspointProvider>> argCaptor = ArgumentCaptor.forClass(
                Map.class);
        // Provider have not provided a successful network connection.
        int expectedInstalledProviders = 1;
        int expectedConnectedProviders = 0;
        when(provider.getHasEverConnected()).thenReturn(false);
        mManager.updateMetrics();
        verify(mWifiMetrics).updateSavedPasspointProfiles(
                eq(expectedInstalledProviders), eq(expectedConnectedProviders));

        verify(mWifiMetrics).updateSavedPasspointProfilesInfo(argCaptor.capture());
        assertEquals(expectedInstalledProviders, argCaptor.getValue().size());
        assertEquals(provider, argCaptor.getValue().get(provider.getConfig().getUniqueId()));
        reset(mWifiMetrics);

        // Provider have provided a successful network connection.
        expectedConnectedProviders = 1;
        when(provider.getHasEverConnected()).thenReturn(true);
        mManager.updateMetrics();
        verify(mWifiMetrics).updateSavedPasspointProfiles(
                eq(expectedInstalledProviders), eq(expectedConnectedProviders));
    }

    /**
     * Verify Passpoint Manager's provisioning APIs by invoking methods in PasspointProvisioner for
     * initiailization and provisioning a provider.
     */
    @Test
    public void verifyPasspointProvisioner() {
        mManager.initializeProvisioner(mLooper.getLooper());
        verify(mPasspointProvisioner).init(any(Looper.class));
        when(mPasspointProvisioner.startSubscriptionProvisioning(anyInt(), any(OsuProvider.class),
                any(IProvisioningCallback.class))).thenReturn(true);
        OsuProvider osuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        assertEquals(true,
                mManager.startSubscriptionProvisioning(TEST_UID, osuProvider, mCallback));
    }

    /**
     * Verify that the corresponding Passpoint provider is removed when the app is disabled.
     */
    @Test
    public void verifyRemovingPasspointProfilesWhenAppIsDisabled() {
        WifiConfiguration currentConfiguration = WifiConfigurationTestUtil.createPasspointNetwork();
        currentConfiguration.FQDN = TEST_FQDN;
        PasspointProvider passpointProvider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        currentConfiguration.setPasspointUniqueId(passpointProvider.getConfig().getUniqueId());
        verify(mAppOpsManager).startWatchingMode(eq(OPSTR_CHANGE_WIFI_STATE), eq(TEST_PACKAGE),
                mAppOpChangedListenerCaptor.capture());
        assertEquals(1, mManager.getProviderConfigs(TEST_CREATOR_UID, true).size());
        AppOpsManager.OnOpChangedListener listener = mAppOpChangedListenerCaptor.getValue();
        assertNotNull(listener);

        // Disallow change wifi state & ensure we remove the profiles from database.
        when(mAppOpsManager.unsafeCheckOpNoThrow(
                OPSTR_CHANGE_WIFI_STATE, TEST_CREATOR_UID,
                TEST_PACKAGE))
                .thenReturn(MODE_IGNORED);
        listener.onOpChanged(OPSTR_CHANGE_WIFI_STATE, TEST_PACKAGE);
        mLooper.dispatchAll();

        verify(mAppOpsManager).stopWatchingMode(mAppOpChangedListenerCaptor.getValue());
        verify(mWifiConfigManager).removePasspointConfiguredNetwork(
                passpointProvider.getWifiConfig().getProfileKey());
        assertTrue(mManager.getProviderConfigs(TEST_CREATOR_UID, true).isEmpty());
    }

    /**
     * Verify that removing a provider with a different UID will not succeed.
     *
     * @throws Exception
     */
    @Test
    public void removeGetProviderWithDifferentUid() throws Exception {
        PasspointConfiguration config = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // no profiles available for TEST_UID
        assertTrue(mManager.getProviderConfigs(TEST_UID, false).isEmpty());
        // 1 profile available for TEST_CREATOR_UID
        assertFalse(mManager.getProviderConfigs(TEST_CREATOR_UID, false).isEmpty());

        // Remove the provider as a non-privileged non-creator app.
        assertFalse(mManager.removeProvider(TEST_UID, false, null, TEST_FQDN));
        verify(provider, never()).uninstallCertsAndKeys();
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderUninstallSuccess();

        // no profiles available for TEST_UID
        assertTrue(mManager.getProviderConfigs(TEST_UID, false).isEmpty());
        // 1 profile available for TEST_CREATOR_UID
        assertFalse(mManager.getProviderConfigs(TEST_CREATOR_UID, false).isEmpty());
    }

    /**
     * Verify that removing a provider from a background user will fail.
     *
     * @throws Exception
     */
    @Test
    public void removeProviderWithBackgroundUser() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verifyInstalledConfig(config);
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();

        when(mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(anyInt()))
                .thenReturn(false);
        assertFalse(mManager.removeProvider(TEST_CREATOR_UID, false, null, TEST_FQDN));
    }

    /**
     * Verify that adding a suggestion provider with a valid configuration and user credential will
     * succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveSuggestionProvider() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(provider.isFromSuggestion()).thenReturn(true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        verify(mAppOpsManager, never()).startWatchingMode(eq(OPSTR_CHANGE_WIFI_STATE),
                eq(TEST_PACKAGE), any(AppOpsManager.OnOpChangedListener.class));
        assertTrue(mManager.getProviderConfigs(TEST_CREATOR_UID, false).isEmpty());
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify content in the data source.
        List<PasspointProvider> providers = mUserDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());
        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Remove from another Suggestor app, should fail.
        assertFalse(mManager.removeProvider(TEST_UID, false, null, TEST_FQDN));
        verify(provider, never()).uninstallCertsAndKeys();
        verify(mWifiConfigManager, never()).removePasspointConfiguredNetwork(
                provider.getWifiConfig().getProfileKey());
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderUninstallSuccess();
        verify(mAppOpsManager, never()).stopWatchingMode(
                any(AppOpsManager.OnOpChangedListener.class));
        // Verify content in the data source.
        providers = mUserDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());
        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mSharedDataSource.getProviderIndex());
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Remove the provider from same app.
        assertTrue(mManager.removeProvider(TEST_CREATOR_UID, false, null, TEST_FQDN));
        verify(provider).uninstallCertsAndKeys();
        verify(mWifiConfigManager, never()).removePasspointConfiguredNetwork(
                provider.getWifiConfig().getProfileKey());
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderUninstallSuccess();
        verify(mAppOpsManager, never()).stopWatchingMode(
                any(AppOpsManager.OnOpChangedListener.class));
        verify(mWifiConfigManager).removeConnectChoiceFromAllNetworks(config.getUniqueId());

        // Verify content in the data source.
        assertTrue(mUserDataSource.getProviders().isEmpty());
        // Removing a provider should not change the provider index.
        assertEquals(1, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a suggestion  provider with the same base domain as the existing
     * suggestion provider from same app will succeed, and verify that the new provider is
     * added along with the existing provider.
     *
     * @throws Exception
     */
    @Test
    public void addSuggestionProviderWithExistingConfig() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider origProvider = createMockProvider(origConfig);
        when(origProvider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(origConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(origProvider);
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify data source content.
        List<PasspointProvider> origProviders = mUserDataSource.getProviders();
        assertEquals(1, origProviders.size());
        assertEquals(origConfig, origProviders.get(0).getConfig());
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Add same provider as existing suggestion provider
        // This should be no WifiConfig deletion
        WifiConfiguration origWifiConfig = origProvider.getWifiConfig();
        origWifiConfig.fromWifiNetworkSuggestion = true;
        origWifiConfig.creatorUid = TEST_CREATOR_UID;
        origWifiConfig.creatorName = TEST_PACKAGE;
        when(mWifiConfigManager.getConfiguredNetwork(origWifiConfig.getProfileKey()))
                .thenReturn(origWifiConfig);
        when(mWifiConfigManager.addOrUpdateNetwork(
                origWifiConfig, TEST_CREATOR_UID, TEST_PACKAGE, false))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));
        verify(mWifiConfigManager, never()).removePasspointConfiguredNetwork(
                origWifiConfig.getProfileKey());
        verify(mWifiConfigManager).addOrUpdateNetwork(
                argThat((c) -> c.FQDN.equals(TEST_FQDN)), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false));
        verify(mWifiConfigManager).allowAutojoin(TEST_NETWORK_ID, origWifiConfig.allowAutojoin);
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        assertEquals(2, mSharedDataSource.getProviderIndex());
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Add another provider with the same base domain as the existing saved provider.
        // This should replace the existing provider with the new configuration.
        PasspointConfiguration newConfig = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider newProvider = createMockProvider(newConfig);
        when(newProvider.isFromSuggestion()).thenReturn(true);
        when(newProvider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(newConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(newProvider);
        assertTrue(mManager.addOrUpdateProvider(newConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();

        // Verify data source content.
        List<PasspointProvider> newProviders = mUserDataSource.getProviders();
        assertEquals(2, newProviders.size());
        assertTrue(newConfig.equals(newProviders.get(0).getConfig())
                || newConfig.equals(newProviders.get(1).getConfig()));
        assertTrue(origConfig.equals(newProviders.get(0).getConfig())
                || origConfig.equals(newProviders.get(1).getConfig()));
        assertEquals(3, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a saved provider with the same base domain as the existing
     * suggestion provider will succeed, and verify that the new provider with the new configuration
     * is added along with the existing provider.
     *
     * @throws Exception
     */
    @Test
    public void addSavedProviderWithExistingSuggestionConfig() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider origProvider = createMockProvider(origConfig);
        when(origProvider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(origConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(origProvider);
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                true, true, false));
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify data source content.
        List<PasspointProvider> origProviders = mUserDataSource.getProviders();
        assertEquals(1, origProviders.size());
        assertEquals(origConfig, origProviders.get(0).getConfig());
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Add another provider with the same base domain as the existing saved provider.
        // This should replace the existing provider with the new configuration.
        PasspointConfiguration newConfig = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider newProvider = createMockProvider(newConfig);
        when(mObjectFactory.makePasspointProvider(eq(newConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(newProvider);
        assertTrue(mManager.addOrUpdateProvider(newConfig, TEST_CREATOR_UID, TEST_PACKAGE,
                false, true, false));
        verify(mWifiConfigManager).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();

        // Verify data source content.
        List<PasspointProvider> newProviders = mUserDataSource.getProviders();
        assertEquals(2, newProviders.size());
        assertTrue(newConfig.equals(newProviders.get(0).getConfig())
                || newConfig.equals(newProviders.get(1).getConfig()));
        assertTrue(origConfig.equals(newProviders.get(0).getConfig())
                || origConfig.equals(newProviders.get(1).getConfig()));
        assertEquals(2, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a suggestion provider with the same base domain as the existing provider
     * from different apps will add a new provider.
     *
     * @throws Exception
     */
    @Test
    public void addSuggestionProviderWithExistingConfigFromDifferentSource() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = createTestConfigWithSimCredential(TEST_FQDN, TEST_IMSI,
                TEST_REALM);
        PasspointProvider origProvider = createMockProvider(origConfig);
        when(origProvider.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mObjectFactory.makePasspointProvider(eq(origConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(false), eq(mClock))).thenReturn(origProvider);
        assertTrue(mManager.addOrUpdateProvider(origConfig, TEST_CREATOR_UID, TEST_PACKAGE, false,
                true, false));
        verifyInstalledConfig(origConfig);
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
        reset(mWifiMetrics);
        reset(mWifiConfigManager);

        // Verify data source content.
        List<PasspointProvider> origProviders = mUserDataSource.getProviders();
        assertEquals(1, origProviders.size());
        assertEquals(origConfig, origProviders.get(0).getConfig());
        assertEquals(1, mSharedDataSource.getProviderIndex());

        // Add another provider with the same base domain as the existing saved provider but from
        // different app. This should not replace the existing provider with the new configuration
        // but add another one.
        PasspointConfiguration newConfig = createTestConfigWithUserCredential(TEST_FQDN,
                TEST_FRIENDLY_NAME);
        PasspointProvider newProvider = createMockProvider(newConfig);
        when(newProvider.isFromSuggestion()).thenReturn(true);
        when(newProvider.getPackageName()).thenReturn(TEST_PACKAGE1);
        when(mObjectFactory.makePasspointProvider(eq(newConfig), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE1),
                eq(true), eq(mClock))).thenReturn(newProvider);
        assertTrue(mManager.addOrUpdateProvider(newConfig, TEST_CREATOR_UID, TEST_PACKAGE1, true,
                true, false));
        verify(mWifiConfigManager, never()).saveToStore();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();

        // Verify data source content.
        List<PasspointProvider> newProviders = mUserDataSource.getProviders();
        assertEquals(2, newProviders.size());
        assertTrue(origConfig.equals(newProviders.get(0).getConfig())
                || origConfig.equals(newProviders.get(1).getConfig()));

        assertEquals(2, mSharedDataSource.getProviderIndex());
    }

    /**
     * Verify that the HomeProvider provider will be returned when a HomeProvider profile has
     * not expired and RoamingProvider expiration is unset (still valid).
     *
     * @throws Exception
     */
    @Test
    public void matchHomeProviderWhenHomeProviderNotExpired() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            PasspointProvider providerHome = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            providerHome.getConfig().setSubscriptionExpirationTimeInMillis(
                    System.currentTimeMillis() + 100000);
            providerHome.getWifiConfig().isHomeProviderNetwork = true;
            PasspointProvider providerRoaming = addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.generateWifiConfig(-1,
                    TEST_UID, "\"PasspointTestSSID\"", true, true,
                    TEST_FQDN + 2, TEST_FRIENDLY_NAME, SECURITY_EAP);
            PasspointProvider providerNone = addTestProvider(TEST_FQDN + 2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, wifiConfiguration, false, null, false);
            ANQPData entry = new ANQPData(mClock, null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID;

            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            when(providerHome.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.HomeProvider);
            when(providerRoaming.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.RoamingProvider);
            when(providerNone.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.None);

            List<Pair<PasspointProvider, PasspointMatch>> results =
                    mManager.matchProvider(createTestScanResult());
            Pair<PasspointProvider, PasspointMatch> result = results.get(0);

            assertEquals(PasspointMatch.HomeProvider, result.second);
            assertEquals(TEST_FQDN, result.first.getConfig().getHomeSp().getFqdn());

        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that the RoamingProvider provider will be returned when a HomeProvider profile has
     * expired and RoamingProvider expiration is unset (still valid).
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingProviderUnsetWhenHomeProviderExpired() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            PasspointProvider providerHome = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            providerHome.getConfig().setSubscriptionExpirationTimeInMillis(
                    System.currentTimeMillis() - 10000);
            providerHome.getWifiConfig().isHomeProviderNetwork = true;
            PasspointProvider providerRoaming = addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.generateWifiConfig(-1,
                    TEST_UID, "\"PasspointTestSSID\"", true, true,
                    TEST_FQDN + 2, TEST_FRIENDLY_NAME, SECURITY_EAP);
            PasspointProvider providerNone = addTestProvider(TEST_FQDN + 2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, wifiConfiguration, false, null, false);
            ANQPData entry = new ANQPData(mClock, null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID;

            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            when(providerHome.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.HomeProvider);
            when(providerRoaming.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.RoamingProvider);
            when(providerNone.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.None);

            List<Pair<PasspointProvider, PasspointMatch>> results =
                    mManager.matchProvider(createTestScanResult());
            Pair<PasspointProvider, PasspointMatch> result = results.get(0);

            assertEquals(PasspointMatch.RoamingProvider, result.second);
            assertEquals(TEST_FQDN2, result.first.getConfig().getHomeSp().getFqdn());

        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that the RoamingProvider provider will be returned when a HomeProvider profile has
     * expired and RoamingProvider expiration is still valid.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingProviderNonExpiredWhenHomeProviderExpired() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            PasspointProvider providerHome = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            providerHome.getConfig().setSubscriptionExpirationTimeInMillis(
                    System.currentTimeMillis() - 10000);
            providerHome.getWifiConfig().isHomeProviderNetwork = true;
            PasspointProvider providerRoaming = addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, false, null, false);
            providerRoaming.getConfig().setSubscriptionExpirationTimeInMillis(
                    System.currentTimeMillis() + 100000);
            WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.generateWifiConfig(-1,
                    TEST_UID, "\"PasspointTestSSID\"", true, true,
                    TEST_FQDN + 2, TEST_FRIENDLY_NAME, SECURITY_EAP);
            PasspointProvider providerNone = addTestProvider(TEST_FQDN + 2, TEST_FRIENDLY_NAME,
                    TEST_PACKAGE, wifiConfiguration, false, null, false);
            ANQPData entry = new ANQPData(mClock, null);
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = TEST_ANQP_DOMAIN_ID;

            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            when(providerHome.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.HomeProvider);
            when(providerRoaming.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.RoamingProvider);
            when(providerNone.match(anyMap(), isNull(), any(ScanResult.class)))
                    .thenReturn(PasspointMatch.None);

            List<Pair<PasspointProvider, PasspointMatch>> results =
                    mManager.matchProvider(createTestScanResult());
            Pair<PasspointProvider, PasspointMatch> result = results.get(0);

            assertEquals(PasspointMatch.RoamingProvider, result.second);
            assertEquals(TEST_FQDN2, result.first.getConfig().getHomeSp().getFqdn());

        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify add untrusted passpoint network from suggestion success.
     */
    @Test
    public void testAddUntrustedPasspointNetworkFromSuggestion() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config, wifiConfig, true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, true, false, false));
        verify(provider).setTrusted(false);
    }

    /**
     * Verify add untrusted passpoint network not from suggestion fail.
     */
    @Test
    public void testAddUntrustedPasspointNetworkNotFromSuggestion() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config, wifiConfig, false);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertFalse(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, false, false, false));
        verify(provider, never()).setTrusted(false);
    }

    /**
     * Verify add restrict passpoint network from suggestion success.
     */
    @Test
    public void testAddRestrictPasspointNetworkFromSuggestion() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config, wifiConfig, true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, true, true, true));
        verify(provider).setRestricted(true);
    }

    /**
     * Verify add restrict passpoint network not from suggestion fail.
     */
    @Test
    public void testAddRestrictPasspointNetworkNotFromSuggestion() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config, wifiConfig, false);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
                eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertFalse(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, false, true, true));
        verify(provider, never()).setRestricted(anyBoolean());
    }

    /**
     * Verify that the ScanResults(Access Points) are returned when it may be
     * authenticated with the provided passpoint configuration as roaming match.
     */
    @Test
    public void getMatchingScanResultsTestWithRoamingMatch() {
        PasspointConfiguration config = mock(PasspointConfiguration.class);
        PasspointProvider mockProvider = mock(PasspointProvider.class);
        when(mObjectFactory.makePasspointProvider(config, null,
                mWifiCarrierInfoManager, 0, 0, null, false, mClock))
                .thenReturn(mockProvider);
        List<ScanResult> scanResults = List.of(mock(ScanResult.class));
        when(mockProvider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
                .thenReturn(PasspointMatch.RoamingProvider);

        List<ScanResult> testResults = mManager.getMatchingScanResults(config, scanResults);

        assertEquals(1, testResults.size());
    }

    /**
     * Verify that the ScanResults(Access Points) are returned when it may be
     * authenticated with the provided passpoint configuration as home match.
     */
    @Test
    public void getMatchingScanResultsTestWithHomeMatch() {
        PasspointConfiguration config = mock(PasspointConfiguration.class);
        PasspointProvider mockProvider = mock(PasspointProvider.class);
        when(mObjectFactory.makePasspointProvider(config, null,
                mWifiCarrierInfoManager, 0, 0, null, false, mClock))
                .thenReturn(mockProvider);
        List<ScanResult> scanResults = List.of(mock(ScanResult.class));
        when(mockProvider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
                .thenReturn(PasspointMatch.HomeProvider);

        List<ScanResult> testResults = mManager.getMatchingScanResults(config, scanResults);

        assertEquals(1, testResults.size());
    }

    /**
     * Verify that the ScanResults(Access Points) are not returned when it cannot be
     * authenticated with the provided passpoint configuration as none match.
     */
    @Test
    public void getMatchingScanResultsTestWithNonMatch() {
        PasspointConfiguration config = mock(PasspointConfiguration.class);

        PasspointProvider mockProvider = mock(PasspointProvider.class);

        when(mObjectFactory.makePasspointProvider(config, null,
                mWifiCarrierInfoManager, 0, 0, null, false, mClock))
                .thenReturn(mockProvider);

        List<ScanResult> scanResults = List.of(mock(ScanResult.class));
        when(mockProvider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
                .thenReturn(PasspointMatch.None);

        List<ScanResult> testResults = mManager.getMatchingScanResults(config, scanResults);

        assertEquals(0, testResults.size());
    }

    /**
     * Verify that no ANQP queries are requested when not allowed (i.e. by WifiMetrics) when
     * there is a cache miss.
     */
    @Test
    public void testAnqpRequestNotAllowed() {
        reset(mWifiConfigManager);
        when(mAnqpCache.getEntry(TEST_ANQP_KEY2)).thenReturn(null);
        verify(mAnqpRequestManager, never()).requestANQPElements(any(long.class),
                any(ANQPNetworkKey.class), any(boolean.class), any(NetworkDetail.HSRelease.class));
    }

    /**
     * Verify that removing of multiple providers with the same FQDN is done correctly.
     */
    @Test
    public void removeAllProvidersWithSameFqdn() {
        PasspointProvider provider1 = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, TEST_REALM, false);
        PasspointProvider provider2 = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, TEST_REALM2, false);
        PasspointProvider provider3 = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, TEST_REALM3, false);

        List<PasspointProvider> providers = mUserDataSource.getProviders();
        assertEquals(3, providers.size());
        verify(mWifiMetrics, times(3)).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics, times(3)).incrementNumPasspointProviderInstallSuccess();

        // Remove the provider as the creator app.
        assertTrue(mManager.removeProvider(TEST_CREATOR_UID, false, null, TEST_FQDN));

        verify(provider1).uninstallCertsAndKeys();
        verify(mWifiConfigManager, times(1)).removePasspointConfiguredNetwork(
                provider1.getWifiConfig().getProfileKey());
        verify(provider2).uninstallCertsAndKeys();
        verify(mWifiConfigManager, times(1)).removePasspointConfiguredNetwork(
                provider2.getWifiConfig().getProfileKey());
        verify(provider3).uninstallCertsAndKeys();
        verify(mWifiConfigManager, times(1)).removePasspointConfiguredNetwork(
                provider3.getWifiConfig().getProfileKey());

        verify(mWifiMetrics, times(3)).incrementNumPasspointProviderUninstallation();
        verify(mWifiMetrics, times(3)).incrementNumPasspointProviderUninstallSuccess();
        verify(mAppOpsManager).stopWatchingMode(any(AppOpsManager.OnOpChangedListener.class));
        assertTrue(mManager.getProviderConfigs(TEST_CREATOR_UID, false).isEmpty());
        verify(mWifiConfigManager, times(3)).removeConnectChoiceFromAllNetworks(any());

        // Verify content in the data source.
        assertTrue(mUserDataSource.getProviders().isEmpty());
    }

    /**
     * Verify that adding a provider with a self signed root CA increments the metrics correctly.
     *
     * @throws Exception
     */
    @Test
    public void verifySelfSignRootCaMetrics() throws Exception {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        PasspointProvider provider = createMockProvider(config, wifiConfig, true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
            eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
            eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, true, false, false));
        verify(mWifiMetrics).incrementNumPasspointProviderWithSelfSignedRootCa();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderWithNoRootCa();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider with no root CA increments the metrics correctly.
     *
     * @throws Exception
     */
    @Test
    public void verifyNoRootCaMetrics() throws Exception {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        config.getCredential().setCaCertificate(null);
        PasspointProvider provider = createMockProvider(config, wifiConfig, true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
            eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
            eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, true, false, false));
        verify(mWifiMetrics).incrementNumPasspointProviderWithNoRootCa();
        verify(mWifiMetrics, never()).incrementNumPasspointProviderWithSelfSignedRootCa();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that adding a provider with subscription expiration increments the metrics correctly.
     *
     * @throws Exception
     */
    @Test
    public void verifySubscriptionExpirationMetrics() throws Exception {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        PasspointConfiguration config =
                createTestConfigWithUserCredential(TEST_FQDN, TEST_FRIENDLY_NAME);
        config.setSubscriptionExpirationTimeInMillis(1586228641000L);
        PasspointProvider provider = createMockProvider(config, wifiConfig, true);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
            eq(mWifiCarrierInfoManager), anyLong(), eq(TEST_CREATOR_UID), eq(TEST_PACKAGE),
            eq(true), eq(mClock))).thenReturn(provider);
        when(provider.getPackageName()).thenReturn(TEST_PACKAGE);
        assertTrue(mManager.addOrUpdateProvider(
                config, TEST_CREATOR_UID, TEST_PACKAGE, true, false, false));
        verify(mWifiMetrics).incrementNumPasspointProviderWithSubscriptionExpiration();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallation();
        verify(mWifiMetrics).incrementNumPasspointProviderInstallSuccess();
    }

    /**
     * Verify that venue URL ANQP request is sent correctly.
     *
     * @throws Exception
     */
    @Test
    public void verifyRequestVenueUrlAnqpElement() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            ScanResult scanResult = createTestScanResult();
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = scanResult.anqpDomainId;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);
            long bssid = Utils.parseMac(scanResult.BSSID);
            mManager.requestVenueUrlAnqpElement(scanResult);
            verify(mAnqpRequestManager).requestVenueUrlAnqpElement(eq(bssid), any());
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify blocking a matched provider following a Deauthentication-imminent WNM-notification
     */
    @Test
    public void testBlockingProvider() {
        WifiConfiguration wifiConfig = WifiConfigurationTestUtil.generateWifiConfig(10, TEST_UID,
                "\"PasspointTestSSID\"", true, true, TEST_FQDN,
                TEST_FRIENDLY_NAME, SECURITY_EAP);
        wifiConfig.BSSID = TEST_BSSID_STRING;

        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, wifiConfig, false,
                        null, false);
        WnmData event = WnmData.createDeauthImminentEvent(Utils.parseMac(TEST_BSSID_STRING), "",
                true, 30);

        mManager.handleDeauthImminentEvent(event, wifiConfig);
        verify(provider).blockBssOrEss(eq(event.getBssid()), eq(event.isEss()),
                eq(event.getDelay()));
    }

    /**
     * Verify set Anonymous Identity to the right passpoint provider.
     */
    @Test
    public void testSetAnonymousIdentity() {
        WifiConfiguration wifiConfig = WifiConfigurationTestUtil.generateWifiConfig(10, TEST_UID,
                "\"PasspointTestSSID\"", true, true, TEST_FQDN,
                TEST_FRIENDLY_NAME, SECURITY_EAP);

        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, wifiConfig, false,
                        null, false);

        wifiConfig.enterpriseConfig.setAnonymousIdentity(TEST_ANONYMOUS_IDENTITY);
        mManager.setAnonymousIdentity(wifiConfig);
        verify(provider).setAnonymousIdentity(TEST_ANONYMOUS_IDENTITY);


        mManager.resetSimPasspointNetwork();
        verify(provider).setAnonymousIdentity(null);
        verify(mWifiConfigManager, times(3)).saveToStore();
    }

    /**
     * Test set and remove user connect choice.
     */
    @Test
    public void testSetUserConnectChoice() {
        WifiConfiguration wifiConfig = WifiConfigurationTestUtil.generateWifiConfig(10, TEST_UID,
                "\"PasspointTestSSID\"", true, true, TEST_FQDN,
                TEST_FRIENDLY_NAME, SECURITY_EAP);

        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, wifiConfig, false,
                        null, false);

        WifiConfiguration wifiConfig2 = WifiConfigurationTestUtil.generateWifiConfig(11, TEST_UID,
                "\"PasspointTestSSID\"", true, true, TEST_FQDN2,
                TEST_FRIENDLY_NAME, SECURITY_EAP);

        PasspointProvider provider2 =
                addTestProvider(TEST_FQDN2, TEST_FRIENDLY_NAME, TEST_PACKAGE, wifiConfig2, false,
                        null, false);

        WifiConfigManager.OnNetworkUpdateListener listener = mNetworkListenerCaptor.getValue();
        reset(mWifiConfigManager);

        // Set user connect choice on this passpoint network
        listener.onConnectChoiceSet(Collections.singletonList(wifiConfig), USER_CONNECT_CHOICE,
                TEST_RSSI);
        verify(provider).setUserConnectChoice(USER_CONNECT_CHOICE, TEST_RSSI);

        // The user connect choice is this psspoint network, its user connect choice should null
        listener.onConnectChoiceSet(Collections.emptyList(), wifiConfig.getPasspointUniqueId(),
                TEST_RSSI);
        verify(provider).setUserConnectChoice(null, 0);

        // Remove the user connect choice, if equals, user connect choice should set to null
        when(provider.getConnectChoice()).thenReturn(USER_CONNECT_CHOICE);
        listener.onConnectChoiceRemoved(USER_CONNECT_CHOICE);
        verify(provider, times(2)).setUserConnectChoice(null, 0);

        verify(provider2, never()).setUserConnectChoice(any(), anyInt());
        verify(mWifiConfigManager, times(3)).saveToStore();

        reset(mWifiConfigManager);
        when(provider.getConnectChoice()).thenReturn(null);
        listener.onConnectChoiceRemoved(USER_CONNECT_CHOICE);
        listener.onConnectChoiceRemoved(null);
        verify(mWifiConfigManager, never()).saveToStore();
    }

    /*
     * Verify that Passpoint manager returns the correct venue URL.
     *
     * @throws Exception
     */
    @Test
    public void testGetVenueUrl() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            ScanResult scanResult = createTestScanResult();
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = scanResult.anqpDomainId;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);

            Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
            anqpElementMap.put(ANQPElementType.ANQPDomName,
                    new DomainNameElement(Arrays.asList(new String[]{"test.com"})));
            List<I18Name> names = new ArrayList<>();
            names.add(new I18Name(TEST_LOCALE_ENGLISH,
                    new Locale.Builder().setLanguage(TEST_LOCALE_ENGLISH).build(),
                    "Passpoint Venue"));
            names.add(new I18Name(TEST_LOCALE_HEBREW,
                    new Locale.Builder().setLanguage(TEST_LOCALE_HEBREW).build(), "רשת פאספוינט"));
            anqpElementMap.put(ANQPElementType.ANQPVenueName, new VenueNameElement(names));

            Map<Integer, URL> venueUrls = new HashMap<>();
            venueUrls.put(1, new URL(TEST_VENUE_URL_ENG));
            venueUrls.put(2, new URL(TEST_VENUE_URL_HEB));
            anqpElementMap.put(ANQPElementType.ANQPVenueUrl, new VenueUrlElement(venueUrls));

            mAnqpCache.addOrUpdateEntry(TEST_ANQP_KEY, anqpElementMap);
            ANQPData entry = new ANQPData(mClock, anqpElementMap);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);

            // Test language 1
            Locale.setDefault(new Locale(TEST_LOCALE_ENGLISH));
            URL venueUrl = mManager.getVenueUrl(scanResult);
            assertEquals(venueUrl.toString(), TEST_VENUE_URL_ENG);

            // Test language 2
            Locale.setDefault(new Locale(TEST_LOCALE_HEBREW));
            venueUrl = mManager.getVenueUrl(scanResult);
            assertEquals(venueUrl.toString(), TEST_VENUE_URL_HEB);

            // Test default language when no language match
            Locale.setDefault(new Locale(TEST_LOCALE_SPANISH));
            venueUrl = mManager.getVenueUrl(scanResult);
            assertEquals(venueUrl.toString(), TEST_VENUE_URL_ENG);
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that Passpoint manager returns null when no ANQP entry is available.
     *
     * @throws Exception
     */
    @Test
    public void testGetVenueUrlNoAnqpEntry() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            ScanResult scanResult = createTestScanResult();
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = scanResult.anqpDomainId;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);

            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);

            URL venueUrl = mManager.getVenueUrl(scanResult);
            assertNull(venueUrl);
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that Passpoint manager returns null when no Venue URL ANQP-element is available.
     *
     * @throws Exception
     */
    @Test
    public void testGetVenueUrlNoVenueUrlAnqpElement() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            ScanResult scanResult = createTestScanResult();
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = scanResult.anqpDomainId;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);

            Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
            anqpElementMap.put(ANQPElementType.ANQPDomName,
                    new DomainNameElement(Arrays.asList(new String[]{"test.com"})));
            List<I18Name> names = new ArrayList<>();
            names.add(new I18Name(TEST_LOCALE_ENGLISH,
                    new Locale.Builder().setLanguage(TEST_LOCALE_ENGLISH).build(),
                    "Passpoint Venue"));
            names.add(new I18Name(TEST_LOCALE_HEBREW,
                    new Locale.Builder().setLanguage(TEST_LOCALE_HEBREW).build(), "רשת פאספוינט"));
            anqpElementMap.put(ANQPElementType.ANQPVenueName, new VenueNameElement(names));

            mAnqpCache.addOrUpdateEntry(TEST_ANQP_KEY, anqpElementMap);
            ANQPData entry = new ANQPData(mClock, anqpElementMap);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);

            URL venueUrl = mManager.getVenueUrl(scanResult);
            assertNull(venueUrl);
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that Passpoint manager returns null when no Venue Name ANQP-element is available.
     *
     * @throws Exception
     */
    @Test
    public void testGetVenueUrlNoVenueNameAnqpElement() throws Exception {
        // static mocking
        MockitoSession session =
                com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession().mockStatic(
                        InformationElementUtil.class).startMocking();
        try {
            ScanResult scanResult = createTestScanResult();
            InformationElementUtil.Vsa vsa = new InformationElementUtil.Vsa();
            vsa.anqpDomainID = scanResult.anqpDomainId;
            when(InformationElementUtil.getHS2VendorSpecificIE(isNull())).thenReturn(vsa);

            Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
            anqpElementMap.put(ANQPElementType.ANQPDomName,
                    new DomainNameElement(Arrays.asList(new String[]{"test.com"})));

            Map<Integer, URL> venueUrls = new HashMap<>();
            venueUrls.put(1, new URL(TEST_VENUE_URL_ENG));
            venueUrls.put(2, new URL(TEST_VENUE_URL_HEB));
            anqpElementMap.put(ANQPElementType.ANQPVenueUrl, new VenueUrlElement(venueUrls));

            mAnqpCache.addOrUpdateEntry(TEST_ANQP_KEY, anqpElementMap);
            ANQPData entry = new ANQPData(mClock, anqpElementMap);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);

            URL venueUrl = mManager.getVenueUrl(scanResult);
            assertNull(venueUrl);

            // Now try with an incomplete list of venue names
            List<I18Name> names = new ArrayList<>();
            names.add(new I18Name(TEST_LOCALE_ENGLISH,
                    new Locale.Builder().setLanguage(TEST_LOCALE_ENGLISH).build(),
                    "Passpoint Venue"));
            anqpElementMap.put(ANQPElementType.ANQPVenueName, new VenueNameElement(names));
            entry = new ANQPData(mClock, anqpElementMap);
            when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);

            venueUrl = mManager.getVenueUrl(scanResult);
            assertNull(venueUrl);
        } finally {
            session.finishMocking();
        }
    }

    /**
     * Verify that Passpoint manager handles the terms and conditions URL correctly: Accepts only
     * HTTPS URLs, and rejects HTTP and invalid URLs.
     *
     * @throws Exception
     */
    @Test
    public void testHandleTermsAndConditionsEvent() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createPasspointNetwork();
        PasspointProvider passpointProvider = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, config, false, null, false);
        assertEquals(TEST_TERMS_AND_CONDITIONS_URL, mManager.handleTermsAndConditionsEvent(
                WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                        TEST_TERMS_AND_CONDITIONS_URL), config).toString());

        // Verify that this provider is never blocked
        verify(passpointProvider, never()).blockBssOrEss(anyLong(), anyBoolean(), anyInt());

        assertNull(mManager.handleTermsAndConditionsEvent(
                WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                        TEST_TERMS_AND_CONDITIONS_URL_NON_HTTPS), config));

        // Verify that the ESS is blocked for 24 hours, the URL is non-HTTPS and unlikely to change
        verify(passpointProvider).blockBssOrEss(eq(TEST_BSSID), eq(true), eq(24 * 60 * 60));

        assertNull(mManager.handleTermsAndConditionsEvent(
                WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                        TEST_TERMS_AND_CONDITIONS_URL_INVALID), config));

        // Verify that the ESS is blocked for an hour due to a temporary issue with the URL
        verify(passpointProvider).blockBssOrEss(eq(TEST_BSSID), eq(true), eq(60 * 60));

        // Now try with a non-Passpoint network
        config = WifiConfigurationTestUtil.createEapNetwork();
        assertNull(mManager.handleTermsAndConditionsEvent(
                WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                        TEST_TERMS_AND_CONDITIONS_URL), config));
        // and a null configuration
        assertNull(mManager.handleTermsAndConditionsEvent(
                WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                        TEST_TERMS_AND_CONDITIONS_URL), null));
    }

    /**
     * Verify that Passpoint manager clears states and flushes caches as expected.
     *
     * @throws Exception
     */
    @Test
    public void testClearAnqpRequestsAndFlushCache() throws Exception {
        PasspointProvider provider = addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME,
                TEST_PACKAGE, false, TEST_REALM, false);

        mManager.clearAnqpRequestsAndFlushCache();
        verify(mAnqpRequestManager).clear();
        verify(mAnqpCache).flush();
        verify(provider).clearProviderBlock();
    }

    /**
     * Verify that when Passpoint manager is enabled/disabled the WifiSettingsStore is updated
     * with correct value.
     *
     * @throws Exception
     */
    @Test
    public void testPasspointEnableSettingsStore() throws Exception {
        // Disable the Wifi Passpoint, check return value and verify status.
        mManager.setWifiPasspointEnabled(false);
        assertFalse(mManager.isWifiPasspointEnabled());
        // Verify WifiSettingStore has been called to set Wifi Passpoint status.
        verify(mWifiSettingsStore).handleWifiPasspointEnabled(false);
        assertFalse(mConfigSettingsPasspointEnabled);

        // Enable the Wifi Passpoint, check return value and verify status.
        mManager.setWifiPasspointEnabled(true);
        assertTrue(mManager.isWifiPasspointEnabled());
        // Verify WifiSettingStore has been called to set Wifi Passpoint status.
        verify(mWifiSettingsStore).handleWifiPasspointEnabled(true);
        assertTrue(mConfigSettingsPasspointEnabled);
    }

    /**
     * Verify that Passpoint manager is enabled and disabled.
     *
     * @throws Exception
     */
    @Test
    public void testPasspointEnableDisable() throws Exception {
        PasspointProvider provider =
                addTestProvider(TEST_FQDN, TEST_FRIENDLY_NAME, TEST_PACKAGE, false, null, false);
        ANQPData entry = new ANQPData(mClock, null);

        when(provider.match(anyMap(), any(RoamingConsortium.class), any(ScanResult.class)))
                .thenReturn(PasspointMatch.HomeProvider);

        // Disable the Wifi Passpoint and expect the matchProvider to return empty list.
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        mManager.setWifiPasspointEnabled(false);
        assertFalse(mManager.isWifiPasspointEnabled());
        assertTrue(mManager.matchProvider(createTestScanResult()).isEmpty());

        // Verify that a request for ANQP elements is not initiated when ANQP cache misses.
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);
        assertTrue(mManager.getAllMatchedProviders(createTestScanResult()).isEmpty());
        verify(mAnqpRequestManager, never()).requestANQPElements(any(long.class),
                any(ANQPNetworkKey.class), any(boolean.class), any(NetworkDetail.HSRelease.class));

        // Enable the Wifi Passpoint and expect the matchProvider to return matched result.
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(entry);
        mManager.setWifiPasspointEnabled(true);
        assertTrue(mManager.isWifiPasspointEnabled());
        List<Pair<PasspointProvider, PasspointMatch>> results =
                mManager.matchProvider(createTestScanResult());
        Pair<PasspointProvider, PasspointMatch> result = results.get(0);
        assertEquals(PasspointMatch.HomeProvider, result.second);
        assertEquals(TEST_FQDN, result.first.getConfig().getHomeSp().getFqdn());

        // Verify that a request for ANQP elements is initiated when ANQP cache misses.
        when(mAnqpCache.getEntry(TEST_ANQP_KEY)).thenReturn(null);
        // ANQP cache misses, still no result.
        assertTrue(mManager.getAllMatchedProviders(createTestScanResult()).isEmpty());
        verify(mAnqpRequestManager).requestANQPElements(eq(TEST_BSSID),
                any(ANQPNetworkKey.class), anyBoolean(), any());
    }
}

