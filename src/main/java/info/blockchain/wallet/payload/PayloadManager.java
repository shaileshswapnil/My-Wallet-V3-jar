package info.blockchain.wallet.payload;

import com.google.common.annotations.VisibleForTesting;
import info.blockchain.bip44.Address;
import info.blockchain.bip44.Wallet;
import info.blockchain.wallet.crypto.AESUtil;
import info.blockchain.wallet.exceptions.*;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.util.*;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.params.MainNetParams;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * PayloadManager.java : singleton class for reading/writing/parsing Blockchain HD JSON payload
 */
public class PayloadManager {

    public static final double SUPPORTED_ENCRYPTION_VERSION = 3.0;
    public static final long NORMAL_ADDRESS = 0L;
    public static final long ARCHIVED_ADDRESS = 2L;
    public static final int RECEIVE_CHAIN = 0;

    private static PayloadManager instance = null;
    // active payload:
    private static Payload payload = null;
    // cached payload, compare to this payload to determine if changes have been made. Used to avoid needless remote saves to server
    private static String cached_payload = null;

    private static CharSequenceX strTempPassword = null;
    private static boolean isNew = false;
    private static String email = null;

    private static double version = 2.0;

    private static BlockchainWallet bciWallet;

    private static HDPayloadBridge hdPayloadBridge;
    private static Wallet wallet;
    private static Wallet watchOnlyWallet;

    private PayloadManager() {
        ;
    }

    /**
     * Return instance for a payload factory.
     *
     * @return PayloadManager
     */
    public static PayloadManager getInstance() {

        if (instance == null) {
            instance = new PayloadManager();
            payload = new Payload();
            cached_payload = "";
            hdPayloadBridge = new HDPayloadBridge();
        }

        return instance;
    }

    /**
     * Reset PayloadManager to null instance.
     */
    public void wipe() {
        instance = null;
    }

    public interface InitiatePayloadListener {
        void onSuccess();
    }

    /**
     * Downloads payload from server, decrypts, and stores as local var {@link Payload}
     *
     * @param sharedKey
     * @param guid
     * @param password
     * @param listener
     */
    public void initiatePayload(@Nonnull String sharedKey, @Nonnull String guid, @Nonnull CharSequenceX password, @Nonnull InitiatePayloadListener listener) throws InvalidCredentialsException, ServerConnectionException, UnsupportedVersionException, PayloadException, DecryptionException, HDWalletException {

        String walletData = null;
        try {
            walletData = fetchWalletData(guid, sharedKey);
        } catch (Exception e) {

            e.printStackTrace();

            if (e.getMessage().contains("Invalid GUID")) {
                throw new InvalidCredentialsException();
            } else {
                throw new ServerConnectionException();
            }
        }

        bciWallet = new BlockchainWallet(walletData, password);
        payload = bciWallet.getPayload();

        if (getVersion() > PayloadManager.SUPPORTED_ENCRYPTION_VERSION) {

            payload = null;
            throw new UnsupportedVersionException(getVersion()+"");
        }

        syncWallet();

        listener.onSuccess();
    }

    /**
     * Fetches wallet data from server
     * @param guid
     * @param sharedKey
     * @return Either encrypted string (v1) or json (v2, v3)
     * @throws Exception
     */
    private String fetchWalletData(String guid, String sharedKey) throws Exception {

        String response = WebUtil.getInstance().postURL(WebUtil.PAYLOAD_URL, "method=wallet.aes.json&guid=" + guid + "&sharedKey=" + sharedKey + "&format=json" + "&api_code=" + WebUtil.API_CODE);

        if (response == null){
            throw new Exception("Payload fetch from server is null");
        }

        return response;

    }

    /**
     * Syncs payload wallet and bip44 wallet
     * @throws HDWalletException
     */
    private void syncWallet() throws HDWalletException {
        if (payload.getHdWallet() != null) {
            if (payload.isDoubleEncrypted()) {
                try {
                    watchOnlyWallet = hdPayloadBridge.getHDWatchOnlyWalletFromXpubs(getXPUBs(true));
                } catch (Exception e) {
                    throw new HDWalletException("Watch-only bip44 wallet error: " + e.getMessage());
                }
            } else {
                try {
                    wallet = hdPayloadBridge.getHDWalletFromPayload(payload);
                } catch (Exception e) {
                    throw new HDWalletException("Bip44 wallet error: " + e.getMessage());
                }
            }
        } else {
            //V2 wallet - no need to keep in sync with bp44 wallet
        }
    }

    /**
     * Get temporary password for user. Read password from here rather than reprompting user.
     *
     * @return CharSequenceX
     */
    public CharSequenceX getTempPassword() {
        return strTempPassword;
    }

    /**
     * Set temporary password for user once it has been validated. Read password from here rather than reprompting user.
     *
     * @param temp_password Validated user password
     */
    public void setTempPassword(CharSequenceX temp_password) {
        strTempPassword = temp_password;
        clearCachedPayload();
    }

    /**
     * Get checksum for this payload.
     *
     * @return String
     */
    public String getCheckSum() {
        return bciWallet.getPayloadChecksum();
    }

    /**
     * Check if this payload is for a new Blockchain account.
     *
     * @return boolean
     */
    public boolean isNew() {
        return isNew;
    }

    /**
     * Set if this payload is for a new Blockchain account.
     *
     * @param isNew
     */
    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    /**
     * Local get(). Returns current payload from the client.
     *
     * @return Payload
     */
    public Payload getPayload() {
        return payload;
    }

    /**
     * Local set(). Sets current payload on the client.
     *
     * @param p Payload to be assigned
     */
    public void setPayload(Payload p) {
        payload = p;
    }

    /**
     * Remote save of current client payload to server. Will not save if no change as compared to cached payload.
     *
     * @return boolean
     */
    public boolean savePayloadToServer() {

        if (payload == null) return false;

        String payloadCleartext = null;

        int iterations = bciWallet.getPbkdf2Iterations();
        boolean syncPubkeys = bciWallet.isSyncPubkeys();
        String oldChecksum = bciWallet.getPayloadChecksum();

        StringBuilder args = new StringBuilder();
        try {

            if (cached_payload != null && cached_payload.equals(payload.dumpJSON().toString())) {
                return true;
            }

            payloadCleartext = payload.dumpJSON().toString();
            String payloadEncrypted = AESUtil.encrypt(payloadCleartext, new CharSequenceX(strTempPassword), iterations);
            JSONObject rootObj = new JSONObject();
            rootObj.put("version", getVersion());
            rootObj.put("pbkdf2_iterations", iterations);
            rootObj.put("payload", payloadEncrypted);

            String strCheckSum = new String(Hex.encode(MessageDigest.getInstance("SHA-256").digest(rootObj.toString().getBytes("UTF-8"))));
            bciWallet.setPayloadChecksum(strCheckSum);

            String method = isNew ? "insert" : "update";

            String urlEncodedPayload = URLEncoder.encode(rootObj.toString());

            args.append("guid=");
            args.append(URLEncoder.encode(payload.getGuid(), "utf-8"));
            args.append("&sharedKey=");
            args.append(URLEncoder.encode(payload.getSharedKey(), "utf-8"));
            args.append("&payload=");
            args.append(urlEncodedPayload);
            args.append("&method=");
            args.append(method);
            args.append("&length=");
            args.append(rootObj.toString().length());
            args.append("&checksum=");
            args.append(URLEncoder.encode(strCheckSum, "utf-8"));

        } catch (NoSuchAlgorithmException nsae) {
            nsae.printStackTrace();
            return false;
        } catch (UnsupportedEncodingException uee) {
            uee.printStackTrace();
            return false;
        } catch (JSONException je) {
            je.printStackTrace();
            return false;
        }

        if (syncPubkeys) {
            args.append("&active=");

            String[] legacyAddrs = null;
            List<LegacyAddress> legacyAddresses = payload.getLegacyAddresses();
            List<String> addrs = new ArrayList<String>();
            for (LegacyAddress addr : legacyAddresses) {
                if (addr.getTag() == 0L) {
                    addrs.add(addr.getAddress());
                }
            }

            args.append(StringUtils.join(addrs.toArray(new String[addrs.size()]), "|"));
        }

        if (email != null && email.length() > 0) {
            try {
                args.append("&email=");
                args.append(URLEncoder.encode(email, "utf-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        args.append("&device=");
        args.append("android");

        if(oldChecksum != null && oldChecksum.length() > 0) {
            args.append("&old_checksum=");
            args.append(oldChecksum);
        }

        args.append("&api_code=" + WebUtil.API_CODE);

        try {
            String response = WebUtil.getInstance().postURL(WebUtil.PAYLOAD_URL, args.toString());
            isNew = false;
            if (response.contains("Wallet successfully synced")) {
                cachePayload();
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * Write to current client payload to cache.
     */
    public void cachePayload() {
        try {
            cached_payload = payload.dumpJSON().toString();
        } catch (JSONException je) {
            je.printStackTrace();
        }
    }

    private void clearCachedPayload() {
        cached_payload = null;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        PayloadManager.email = email;
    }

    public double getVersion() {
        return version;
    }

    @VisibleForTesting
    double setVersion(double version) {
        return this.version = version;
    }

    public boolean validateSecondPassword(String secondPassword) {
        return DoubleEncryptionFactory.getInstance().validateSecondPassword(
                payload.getDoublePasswordHash(),
                payload.getSharedKey(),
                new CharSequenceX(secondPassword),
                payload.getDoubleEncryptionPbkdf2Iterations());
    }

    public boolean decryptDoubleEncryptedWallet(String secondPassword) {

        if (validateSecondPassword(secondPassword)) {

            String encrypted_hex = payload.getHdWallet().getSeedHex();
            String decrypted_hex = DoubleEncryptionFactory.getInstance().decrypt(
                    encrypted_hex,
                    payload.getSharedKey(),
                    secondPassword,
                    payload.getDoubleEncryptionPbkdf2Iterations());

            try {
                watchOnlyWallet = hdPayloadBridge.decryptWatchOnlyWallet(payload, decrypted_hex);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }

            return true;

        } else {
            return false;
        }
    }

    public String[] getMnemonicForDoubleEncryptedWallet(String secondPassword) {

        if (validateSecondPassword(secondPassword)) {
            // Decrypt seedHex (which is double encrypted in this case)
            String decrypted_hex = DoubleEncryptionFactory.getInstance().decrypt(
                    payload.getHdWallet().getSeedHex(),
                    payload.getSharedKey(),
                    secondPassword,
                    payload.getDoubleEncryptionPbkdf2Iterations());

            String mnemonic = null;

            try {
                watchOnlyWallet = hdPayloadBridge.decryptWatchOnlyWallet(payload, decrypted_hex);
                mnemonic = watchOnlyWallet.getMnemonic();

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (mnemonic != null && mnemonic.length() > 0) {

                    return mnemonic.split("\\s+");

                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    public Payload createHDWallet(String payloadPassword, String defaultAccountName) throws Exception {

        setTempPassword(new CharSequenceX(payloadPassword));
        HDPayloadBridge.HDWalletPayloadPair pair = hdPayloadBridge.createHDWallet(defaultAccountName);
        wallet = pair.wallet;
        payload = pair.payload;
        setNew(true);

        bciWallet = new BlockchainWallet(payload);

        if (!savePayloadToServer()) {
            //if save failed don't return payload
            payload = null;
        }

        return payload;
    }

    public Payload restoreHDWallet(String payloadPassword, String seed, String defaultAccountName) throws Exception {

        setTempPassword(new CharSequenceX(payloadPassword));
        HDPayloadBridge.HDWalletPayloadPair pair = hdPayloadBridge.restoreHDWallet(seed, defaultAccountName);
        wallet = pair.wallet;
        payload = pair.payload;
        setNew(true);

        bciWallet = new BlockchainWallet(payload);

        if (!savePayloadToServer()) {
            //if save failed don't return payload
            payload = null;
        }

        return payload;
    }

    public Payload restoreHDWallet(String payloadPassword, String seed, String defaultAccountName, String passphrase) throws Exception {

        setTempPassword(new CharSequenceX(payloadPassword));
        HDPayloadBridge.HDWalletPayloadPair pair = hdPayloadBridge.restoreHDWallet(seed, defaultAccountName, passphrase);
        wallet = pair.wallet;
        payload = pair.payload;
        setNew(true);

        bciWallet = new BlockchainWallet(payload);

        if (!savePayloadToServer()) {
            //if save failed don't return payload
            payload = null;
        }

        return payload;
    }

    public interface UpgradePayloadListener {
        void onDoubleEncryptionPasswordError();

        void onUpgradeSuccess();

        void onUpgradeFail();
    }

    /*
    When called from Android - First apply PRNGFixes
     */
    public void upgradeV2PayloadToV3(CharSequenceX secondPassword, boolean isNewlyCreated, String defaultAccountName, final UpgradePayloadListener listener) throws Exception {

        //Check if payload has 2nd password
        if (payload.isDoubleEncrypted()) {

            //Validate 2nd password
            if (StringUtils.isEmpty(secondPassword) || !validateSecondPassword(secondPassword.toString())) {
                listener.onDoubleEncryptionPasswordError();
            }
        }

        //Upgrade
        boolean isUpgradeSuccessful = hdPayloadBridge.upgradeV2PayloadToV3(payload, secondPassword, isNewlyCreated, defaultAccountName);
        if (isUpgradeSuccessful) {

            if (savePayloadToServer()) {

                syncWallet();
                updateBalancesAndTransactions();
                cachePayload();
                listener.onUpgradeSuccess();
            } else {
                listener.onUpgradeFail();//failed to save
            }
        } else {
            listener.onUpgradeFail();//failed to create
        }

    }

    public String getChangeAddress(int accountIndex) throws Exception {
        int changeIdx = payload.getHdWallet().getAccounts().get(accountIndex).getIdxChangeAddresses();

        if (!payload.isDoubleEncrypted()) {
            return wallet.getAccount(accountIndex).getChange().getAddressAt(changeIdx).getAddressString();
        } else {
            return watchOnlyWallet.getAccount(accountIndex).getChange().getAddressAt(changeIdx).getAddressString();
        }
    }

    public String getReceiveAddress(int accountIndex) {

        try {
            Address addr = null;
            int idx = payload.getHdWallet().getAccounts().get(accountIndex).getIdxReceiveAddresses();
            if (!payload.isDoubleEncrypted()) {
                addr = wallet.getAccount(accountIndex).getChain(RECEIVE_CHAIN).getAddressAt(idx);
            } else {
                addr = watchOnlyWallet.getAccount(accountIndex).getChain(RECEIVE_CHAIN).getAddressAt(idx);
            }

            ReceiveAddress receiveAddress = new ReceiveAddress(addr.getAddressString(), idx);
            return receiveAddress.getAddress();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public ECKey getECKey(int accountIndex, String path) throws Exception {

        String[] s = path.split("/");
        Address hd_address = null;
        if (!payload.isDoubleEncrypted()) {
            hd_address = wallet.getAccount(accountIndex).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
        } else {
            hd_address = watchOnlyWallet.getAccount(accountIndex).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
        }
        return PrivateKeyFactory.getInstance().getKey(PrivateKeyFactory.WIF_COMPRESSED, hd_address.getPrivateKeyString());
    }

    public String getXpubFromAccountIndex(int accountIdx) {
        return payload.getHdWallet().getAccounts().get(accountIdx).getXpub();
    }

    public void updateBalancesAndTransactions() throws Exception {
        // TODO unify legacy and HD call to one API call
        // TODO getXpub must be called before getLegacy (unify should fix this)

        boolean isNotUpgraded = isNotUpgraded();

        // xPub balance
        if (!isNotUpgraded) {
            String[] xpubs = getXPUBs(false);
            if (xpubs.length > 0) {
                MultiAddrFactory.getInstance().refreshXPUBData(xpubs);
            }
            List<Account> accounts = payload.getHdWallet().getAccounts();
            for (Account a : accounts) {
                a.setIdxReceiveAddresses(MultiAddrFactory.getInstance().getHighestTxReceiveIdx(a.getXpub()) > a.getIdxReceiveAddresses() ?
                        MultiAddrFactory.getInstance().getHighestTxReceiveIdx(a.getXpub()) : a.getIdxReceiveAddresses());
                a.setIdxChangeAddresses(MultiAddrFactory.getInstance().getHighestTxChangeIdx(a.getXpub()) > a.getIdxChangeAddresses() ?
                        MultiAddrFactory.getInstance().getHighestTxChangeIdx(a.getXpub()) : a.getIdxChangeAddresses());
            }
        }

        // Balance for legacy addresses
        if (payload.getLegacyAddresses().size() > 0) {
            List<String> legacyAddresses = payload.getLegacyAddressStrings();
            String[] addresses = legacyAddresses.toArray(new String[legacyAddresses.size()]);
            MultiAddrFactory.getInstance().refreshLegacyAddressData(addresses, false);
        }
    }

    public boolean isNotUpgraded() {
        return payload != null && !payload.isUpgraded();
    }

    public String[] getXPUBs(boolean includeArchives) throws IOException, DecoderException, AddressFormatException, MnemonicException.MnemonicLengthException, MnemonicException.MnemonicChecksumException, MnemonicException.MnemonicWordException {

        ArrayList<String> xpubs = new ArrayList<String>();

        if (payload.getHdWallet() != null) {
            int nb_accounts = payload.getHdWallet().getAccounts().size();
            for (int i = 0; i < nb_accounts; i++) {
                boolean isArchived = payload.getHdWallet().getAccounts().get(i).isArchived();
                if (isArchived && !includeArchives) {
                    ;
                } else {
                    String s = payload.getHdWallet().getAccounts().get(i).getXpub();
                    if (s != null && s.length() > 0) {
                        xpubs.add(s);
                    }
                }
            }
        }

        return xpubs.toArray(new String[xpubs.size()]);
    }

    public interface AccountAddListener {
        void onAccountAddSuccess(Account account);

        void onSecondPasswordFail();

        void onPayloadSaveFail();
    }

    public void addAccount(String accountLabel, @Nullable String secondPassword, AccountAddListener listener) throws Exception {

        //Add account
        String xpub = null;
        String xpriv = null;

        if (!payload.isDoubleEncrypted()) {

            wallet.addAccount();

            xpub = wallet.getAccounts().get(wallet.getAccounts().size() - 1).xpubstr();
            xpriv = wallet.getAccounts().get(wallet.getAccounts().size() - 1).xprvstr();
        } else {

            if (DoubleEncryptionFactory.getInstance().validateSecondPassword(
                    payload.getDoublePasswordHash(),
                    payload.getSharedKey(),
                    new CharSequenceX(secondPassword),
                    payload.getOptions().getIterations())) {

                String decrypted_hex = DoubleEncryptionFactory.getInstance().decrypt(
                        payload.getHdWallet().getSeedHex(),
                        payload.getSharedKey(),
                        secondPassword,
                        payload.getDoubleEncryptionPbkdf2Iterations());

                //Need to decrypt watch-only wallet before adding new xpub
                watchOnlyWallet = hdPayloadBridge.decryptWatchOnlyWallet(payload, decrypted_hex);
                watchOnlyWallet.addAccount();

                xpub = watchOnlyWallet.getAccounts().get(watchOnlyWallet.getAccounts().size() - 1).xpubstr();
                xpriv = watchOnlyWallet.getAccounts().get(watchOnlyWallet.getAccounts().size() - 1).xprvstr();

            } else {
                listener.onSecondPasswordFail();
                return;
            }
        }

        //Initialize newly created xpub's tx list and balance
        List<Tx> txs = new ArrayList<Tx>();
        MultiAddrFactory.getInstance().getXpubTxs().put(xpub, txs);
        MultiAddrFactory.getInstance().getXpubAmounts().put(xpub, 0L);

        //Get account list from payload (not in sync with wallet from WalletFactory)
        List<Account> accounts = payload.getHdWallet().getAccounts();

        //Create new account (label, xpub, xpriv)
        Account account = new Account(accountLabel);
        account.setXpub(xpub);
        if (!payload.isDoubleEncrypted()) {
            account.setXpriv(xpriv);
        } else {
            String encrypted_xpriv = DoubleEncryptionFactory.getInstance().encrypt(
                    xpriv,
                    payload.getSharedKey(),
                    secondPassword,
                    payload.getDoubleEncryptionPbkdf2Iterations());
            account.setXpriv(encrypted_xpriv);
        }

        //Add new account to payload
        if (accounts.get(accounts.size() - 1) instanceof ImportedAccount) {
            accounts.add(accounts.size() - 1, account);
        } else {
            accounts.add(account);
        }
        payload.getHdWallet().setAccounts(accounts);

        //Save payload
        if (savePayloadToServer()) {
            if (listener != null) listener.onAccountAddSuccess(account);
        } else {
            if (listener != null) listener.onPayloadSaveFail();
        }
    }

    /*
    Generate V2 legacy address
    When called from Android - First apply PRNGFixes
     */
    public LegacyAddress generateLegacyAddress(String deviceName, String deviceVersion, String secondPassword) {

        if (payload.isDoubleEncrypted() && !validateSecondPassword(secondPassword)) {
            return null;//second password validation failed
        }

        ECKey ecKey = getRandomECKey();

        String encryptedKey = new String(Base58.encode(ecKey.getPrivKeyBytes()));
        if (payload.isDoubleEncrypted()) {
            encryptedKey = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey,
                    payload.getSharedKey(),
                    secondPassword,
                    payload.getOptions().getIterations());
        }

        final LegacyAddress legacyAddress = new LegacyAddress();
        legacyAddress.setEncryptedKey(encryptedKey);
        legacyAddress.setAddress(ecKey.toAddress(MainNetParams.get()).toString());
        legacyAddress.setCreatedDeviceName(deviceName);
        legacyAddress.setCreated(System.currentTimeMillis());
        legacyAddress.setCreatedDeviceVersion(deviceVersion);

        return legacyAddress;
    }

    public boolean addLegacyAddress(LegacyAddress legacyAddress) {
        List<LegacyAddress> updatedLegacyAddresses = payload.getLegacyAddresses();
        updatedLegacyAddresses.add(legacyAddress);
        payload.setLegacyAddresses(updatedLegacyAddresses);
        return savePayloadToServer();
    }

    private ECKey getRandomECKey() {

        String result = null;
        byte[] data = null;
        try {
            result = WebUtil.getInstance().getURL(WebUtil.EXTERNAL_ENTROPY_URL);
            if (!result.matches("^[A-Fa-f0-9]{64}$")) {
                return null;
            }
            data = Hex.decode(result);
        } catch (Exception e) {
            return null;
        }

        ECKey ecKey = null;
        if (data != null) {
            byte[] rdata = new byte[32];
            SecureRandom random = new SecureRandom();
            random.nextBytes(rdata);
            byte[] privbytes = Util.getInstance().xor(data, rdata);
            if (privbytes == null) {
                return null;
            }
            ecKey = ECKey.fromPrivate(privbytes, true);
            // erase all byte arrays:
            random.nextBytes(privbytes);
            random.nextBytes(rdata);
            random.nextBytes(data);
        } else {
            return null;
        }

        return ecKey;
    }

    public String getHDSeed() throws IOException, MnemonicException.MnemonicLengthException {
        return wallet.getSeedHex();
    }

    public String getHDMnemonic() throws IOException, MnemonicException.MnemonicLengthException {
        return wallet.getMnemonic();
    }

    public String getHDPassphrase() throws IOException, MnemonicException.MnemonicLengthException {
        return wallet.getPassphrase();
    }

    public Address getAddressAt(int accountIndex, int chain, int addressIndex) {
        Address hd_address = null;
        if (!payload.isDoubleEncrypted()) {
            hd_address = wallet.getAccount(accountIndex).getChain(chain).getAddressAt(addressIndex);
        } else {
            hd_address = watchOnlyWallet.getAccount(accountIndex).getChain(chain).getAddressAt(addressIndex);
        }

        return hd_address;
    }

    /**
     * Debugging purposes
     * @return
     */
    public BlockchainWallet getBciWallet(){
        return bciWallet;
    }
}
