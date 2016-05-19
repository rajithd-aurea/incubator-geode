/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.distributed.internal.membership.gms.messenger;


import io.codearte.catchexception.shade.mockito.cglib.core.Local;

import java.math.BigInteger;
import java.security.*;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.NetView;
import com.gemstone.gemfire.distributed.internal.membership.gms.Services;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.logging.LogService;

public class GMSEncrypt implements Cloneable{
  
  public static long encodingsPerformed;
  public static long decodingsPerformed;

  private static final Logger logger = LogService.getLogger();

  // Parameters for the Diffie-Hellman key exchange
  private static final BigInteger dhP = new BigInteger(
    "13528702063991073999718992897071702177131142188276542919088770094024269"
      +  "73079899070080419278066109785292538223079165925365098181867673946"
      +  "34756714063947534092593553024224277712367371302394452615862654308"
      +  "11180902979719649450105660478776364198726078338308557022096810447"
      +  "3500348898008043285865193451061481841186553");

  private static final BigInteger dhG = new BigInteger(
    "13058345680719715096166513407513969537624553636623932169016704425008150"
      +  "56576152779768716554354314319087014857769741104157332735258102835"
      +  "93126577393912282416840649805564834470583437473176415335737232689"
      +  "81480201869671811010996732593655666464627559582258861254878896534"
      +  "1273697569202082715873518528062345259949959");

  private static final int dhL = 1023;

  private  PrivateKey dhPrivateKey = null;

  private  PublicKey dhPublicKey = null;

  private  String dhSKAlgo = null;

  private Services services;
  
  private InternalDistributedMember localMember;

  private NetView view;

  private Map<InternalDistributedMember, PeerEncryptor> memberToPeerEncryptor = new ConcurrentHashMap<>();
  
  private ClusterEncryptor clusterEncryptor;


  protected void installView(NetView view) throws Exception {
    this.view = view;
    this.view.setPublicKey(services.getJoinLeave().getMemberID(), getPublicKeyBytes());
  }
  
  protected void installView(NetView view, InternalDistributedMember mbr) throws Exception {
    this.view = view;
   // this.view.setPublicKey(mbr, getPublicKeyBytes());
    // TODO remove ciphers for departed members
    //addClusterKey();
  }
  
  protected byte[] getSecretBytes() {
    return this.clusterEncryptor.secretBytes;
  }
  
  protected synchronized void addClusterKey() throws Exception {
    this.clusterEncryptor = new ClusterEncryptor(this);
  }
  
  protected synchronized void addClusterKey(byte[] secretBytes) throws Exception {
    this.clusterEncryptor = new ClusterEncryptor(secretBytes);
  }

  protected GMSEncrypt() {
    
  }

  public GMSEncrypt(Services services) throws  Exception {
    this.services = services;
    initDHKeys(services.getConfig().getDistributionConfig());
  }
  
  public GMSEncrypt(Services services, InternalDistributedMember mbr) throws  Exception {
    this.services = services;
    this.localMember = mbr;
    initDHKeys(services.getConfig().getDistributionConfig());
  }

  public byte[] decryptData(byte[] data, InternalDistributedMember member) throws Exception {
    return getPeerEncryptor(member).decryptBytes(data);
  }

  public byte[] encryptData(byte[] data, InternalDistributedMember member) throws Exception {
    return getPeerEncryptor(member).encryptBytes(data);
  }
  

  public byte[] decryptData(byte[] data) throws Exception {
    return this.clusterEncryptor.decryptBytes(data);
  }

  public byte[] encryptData(byte[] data) throws Exception {
    return this.clusterEncryptor.encryptBytes(data);
  }

  protected byte[] getPublicKeyBytes() {
    return dhPublicKey.getEncoded();
  }

  @Override
  protected GMSEncrypt clone() throws CloneNotSupportedException {
    try {
      GMSEncrypt gmsEncrypt = new GMSEncrypt();
      gmsEncrypt.localMember = this.localMember;
      gmsEncrypt.dhSKAlgo = this.dhSKAlgo;

      X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(this.dhPublicKey.getEncoded());
      KeyFactory keyFact = KeyFactory.getInstance("DH");
      // PublicKey pubKey = keyFact.generatePublic(x509KeySpec);
      gmsEncrypt.dhPublicKey = keyFact.generatePublic(x509KeySpec);
      final String format = this.dhPrivateKey.getFormat();
      System.out.println("private key format " + format);
      System.out.println("public ksy format " + this.dhPublicKey.getFormat());
      PKCS8EncodedKeySpec x509KeySpecPKey = new PKCS8EncodedKeySpec(this.dhPrivateKey.getEncoded());
      
      keyFact = KeyFactory.getInstance("DH");
      // PublicKey pubKey = keyFact.generatePublic(x509KeySpec);
      gmsEncrypt.dhPrivateKey = keyFact.generatePrivate(x509KeySpecPKey);

      return gmsEncrypt;
    } catch (Exception e) {
      throw new RuntimeException("Unable to clone", e);
    }
  }

  /**
   * Initialize the Diffie-Hellman keys. This method is not thread safe
   */
  private void initDHKeys(DistributionConfig config) throws Exception {

    dhSKAlgo = config.getSecurityClientDHAlgo();
    // Initialize the keys when either the host is a peer that has
    // non-blank setting for DH symmetric algo, or this is a server
    // that has authenticator defined.
    if ((dhSKAlgo != null && dhSKAlgo.length() > 0)) {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
      DHParameterSpec dhSpec = new DHParameterSpec(dhP, dhG, dhL);
      keyGen.initialize(dhSpec);
      KeyPair keypair = keyGen.generateKeyPair();

      // Get the generated public and private keys
      dhPrivateKey = keypair.getPrivate();
      dhPublicKey = keypair.getPublic();
    }
  }

  protected synchronized PeerEncryptor getPeerEncryptor(InternalDistributedMember member) throws Exception{
    PeerEncryptor result = memberToPeerEncryptor.get(member);
    if (result == null) {
      result = createPeerEncryptor(member);
    }
    return result;
  }

  private PeerEncryptor createPeerEncryptor(InternalDistributedMember member) throws Exception {
    byte[] peerKeyBytes = (byte[]) view.getPublicKey(member);
    PeerEncryptor result = new PeerEncryptor(peerKeyBytes);
    memberToPeerEncryptor.put(member, result);
    return result;
  }


  private static int getKeySize(String skAlgo) {
    // skAlgo contain both algo and key size info
    int colIdx = skAlgo.indexOf(':');
    String algoStr;
    int algoKeySize = 0;
    if (colIdx >= 0) {
      algoStr = skAlgo.substring(0, colIdx);
      algoKeySize = Integer.parseInt(skAlgo.substring(colIdx + 1));
    }
    else {
      algoStr = skAlgo;
    }
    int keysize = -1;
    if (algoStr.equalsIgnoreCase("DESede")) {
      keysize = 24;
    }
    else if (algoStr.equalsIgnoreCase("Blowfish")) {
      keysize = algoKeySize > 128 ? algoKeySize / 8 : 16;
    }
    else if (algoStr.equalsIgnoreCase("AES")) {
      keysize = (algoKeySize != 192 && algoKeySize != 256) ? 16
        : algoKeySize / 8;
    }
    return keysize;
  }

  private static String getDhAlgoStr(String skAlgo) {
    int colIdx = skAlgo.indexOf(':');
    String algoStr;
    if (colIdx >= 0) {
      algoStr = skAlgo.substring(0, colIdx);
    }
    else {
      algoStr = skAlgo;
    }
    return algoStr;
  }

  private static int getBlockSize(String skAlgo) {
    int blocksize = -1;
    String algoStr = getDhAlgoStr(skAlgo);
    if (algoStr.equalsIgnoreCase("DESede")) {
      blocksize = 8;
    }
    else if (algoStr.equalsIgnoreCase("Blowfish")) {
      blocksize = 8;
    }
    else if (algoStr.equalsIgnoreCase("AES")) {
      blocksize = 16;
    }
    return blocksize;
  }

  static public byte[] encryptBytes(byte[] data, Cipher encrypt) throws Exception{
    synchronized(GMSEncrypt.class) {
      encodingsPerformed++;
    }
    return encrypt.doFinal(data);
  }

  static public byte[] decryptBytes(byte[] data, Cipher decrypt)
    throws Exception{
    try {
      byte[] decryptBytes = decrypt.doFinal(data);
      synchronized(GMSEncrypt.class) {
        decodingsPerformed++;
      }
      return decryptBytes;
    }catch(Exception ex) {
      throw ex;
    }
  }


  protected class PeerEncryptor {

    private PublicKey peerPublicKey = null;

    private String peerSKAlgo = null;

    private Cipher encrypt;
    
    private Cipher decrypt = null;

    protected PeerEncryptor(byte[] peerPublicKeyBytes) throws Exception {
      this.peerPublicKey = getPublicKey(peerPublicKeyBytes);
    }

    public byte [] encryptBytes(byte[] data) throws Exception {
      String algo = null;
      if (this.peerSKAlgo != null) {
        algo = this.peerSKAlgo;
      } else {
        algo = dhSKAlgo;
      }
      return GMSEncrypt.encryptBytes(data, getEncryptCipher(algo));
    }

    private Cipher getEncryptCipher(String dhSKAlgo)
      throws Exception{
      try {
        if(encrypt == null) {
          encrypt = GMSEncrypt.getEncryptCipher(dhSKAlgo, dhPrivateKey, this.peerPublicKey);
        }
      }catch(Exception ex) {
        throw ex;
      }
      return encrypt;
    }
    
    public byte[] decryptBytes(byte[] data) throws Exception
    {
      String algo = null;
      if (this.peerSKAlgo != null) {
        algo = this.peerSKAlgo;
      } else {
        algo = dhSKAlgo;
      }
      Cipher c = getDecryptCipher(algo, this.peerPublicKey);
      return GMSEncrypt.decryptBytes(data, c);

    }    

    private Cipher getDecryptCipher( String dhSKAlgo, PublicKey publicKey)
      throws Exception{
      if(decrypt == null) {
        decrypt = GMSEncrypt.getDecryptCipher(dhSKAlgo, dhPrivateKey, this.peerPublicKey);
      }
      return decrypt;
    }

  }

  protected static Cipher getEncryptCipher(String dhSKAlgo, PrivateKey privateKey, PublicKey peerPublicKey) 
    throws Exception{
    KeyAgreement ka = KeyAgreement.getInstance("DH");
    ka.init(privateKey);
    ka.doPhase(peerPublicKey, true);
    
    Cipher encrypt;

    int keysize = getKeySize(dhSKAlgo);
    int blocksize = getBlockSize(dhSKAlgo);

    if (keysize == -1 || blocksize == -1) {
      SecretKey sKey = ka.generateSecret(dhSKAlgo);
      encrypt = Cipher.getInstance(dhSKAlgo);
      encrypt.init(Cipher.ENCRYPT_MODE, sKey);
    }
    else {
      String dhAlgoStr = getDhAlgoStr(dhSKAlgo);

      byte[] sKeyBytes = ka.generateSecret();
      SecretKeySpec sks = new SecretKeySpec(sKeyBytes, 0, keysize, dhAlgoStr);
      IvParameterSpec ivps = new IvParameterSpec(sKeyBytes, keysize, blocksize);

      encrypt = Cipher.getInstance(dhAlgoStr + "/CBC/PKCS5Padding");
      encrypt.init(Cipher.ENCRYPT_MODE, sks, ivps);
    }

    return encrypt;
  }
  
  protected static Cipher getEncryptCipher(String dhSKAlgo, byte[] secretBytes) 
      throws Exception{
      
      Cipher encrypt = null;

      int keysize = getKeySize(dhSKAlgo);
      int blocksize = getBlockSize(dhSKAlgo);

      if (keysize == -1 || blocksize == -1) {
        //TODO how should we do here
        /*SecretKey sKey = ka.generateSecret(dhSKAlgo);
        encrypt = Cipher.getInstance(dhSKAlgo);
        encrypt.init(Cipher.ENCRYPT_MODE, sKey);*/
      }
      else {
        String dhAlgoStr = getDhAlgoStr(dhSKAlgo);

        SecretKeySpec sks = new SecretKeySpec(secretBytes, 0, keysize, dhAlgoStr);
        IvParameterSpec ivps = new IvParameterSpec(secretBytes, keysize, blocksize);

        encrypt = Cipher.getInstance(dhAlgoStr + "/CBC/PKCS5Padding");
        encrypt.init(Cipher.ENCRYPT_MODE, sks, ivps);
      }

      return encrypt;
    }
  
  protected static Cipher getDecryptCipher(String dhSKAlgo, PrivateKey privateKey, PublicKey publicKey) throws Exception {
    KeyAgreement ka = KeyAgreement.getInstance("DH");
    ka.init(privateKey);
    ka.doPhase(publicKey, true);
    
    Cipher decrypt;

    int keysize = getKeySize(dhSKAlgo);
    int blocksize = getBlockSize(dhSKAlgo);

    if (keysize == -1 || blocksize == -1) {
      SecretKey sKey = ka.generateSecret(dhSKAlgo);
      decrypt = Cipher.getInstance(dhSKAlgo);
      decrypt.init(Cipher.DECRYPT_MODE, sKey);
    } else {
      String algoStr = getDhAlgoStr(dhSKAlgo);

      byte[] sKeyBytes = ka.generateSecret();
      SecretKeySpec sks = new SecretKeySpec(sKeyBytes, 0, keysize, algoStr);
      IvParameterSpec ivps = new IvParameterSpec(sKeyBytes, keysize, blocksize);

      decrypt = Cipher.getInstance(algoStr + "/CBC/PKCS5Padding");
      decrypt.init(Cipher.DECRYPT_MODE, sks, ivps);
    }
    return decrypt;
  }
  
  protected static Cipher getDecryptCipher(String dhSKAlgo, byte[] secretBytes) throws Exception {
    Cipher decrypt = null;

    int keysize = getKeySize(dhSKAlgo);
    int blocksize = getBlockSize(dhSKAlgo);

    if (keysize == -1 || blocksize == -1) {
      //TODO: how to do here
      /*SecretKey sKey = ka.generateSecret(dhSKAlgo);
      decrypt = Cipher.getInstance(dhSKAlgo);
      decrypt.init(Cipher.DECRYPT_MODE, sKey);*/
    } else {
      String algoStr = getDhAlgoStr(dhSKAlgo);

      SecretKeySpec sks = new SecretKeySpec(secretBytes, 0, keysize, algoStr);
      IvParameterSpec ivps = new IvParameterSpec(secretBytes, keysize, blocksize);

      decrypt = Cipher.getInstance(algoStr + "/CBC/PKCS5Padding");
      decrypt.init(Cipher.DECRYPT_MODE, sks, ivps);
    }
    return decrypt;
  }
  protected static byte[] generateSecret(String dhSKAlgo, PrivateKey privateKey, PublicKey otherPublicKey) throws Exception {
    KeyAgreement ka = KeyAgreement.getInstance("DH");
    ka.init(privateKey);
    ka.doPhase(otherPublicKey, true);
    
    int keysize = getKeySize(dhSKAlgo);
    int blocksize = getBlockSize(dhSKAlgo);

    if (keysize == -1 || blocksize == -1) {
      SecretKey sKey = ka.generateSecret(dhSKAlgo);
      return sKey.getEncoded();
    } else {
      String algoStr = getDhAlgoStr(dhSKAlgo);

      return ka.generateSecret();
    }
  }
  
  protected static PublicKey getPublicKey(byte[] publicKeyBytes) throws Exception {
    X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(publicKeyBytes);
    KeyFactory keyFact = KeyFactory.getInstance("DH");
    //PublicKey pubKey = keyFact.generatePublic(x509KeySpec);
    return keyFact.generatePublic(x509KeySpec);
  }

  protected static void initEncryptCipher(KeyAgreement ka, List<PublicKey> publicKeys) throws Exception{
    Iterator<PublicKey> itr = publicKeys.iterator();
    while(itr.hasNext()) {
       ka.doPhase(itr.next(), !itr.hasNext());
    }
  }
  /***
   * this will hold the common key for cluster
   * that will be created using publickey of all the members..
   *
   */
  protected class ClusterEncryptor{
    byte[] secretBytes;
    Cipher encrypt;
    Cipher decrypt;
    int viewId;
    Set<InternalDistributedMember> mbrs;
    
    public ClusterEncryptor(GMSEncrypt other) throws Exception {
      GMSEncrypt mine = new GMSEncrypt(other.services);      
      this.secretBytes = GMSEncrypt.generateSecret(mine.dhSKAlgo, mine.dhPrivateKey, other.dhPublicKey);
    }
    
    public ClusterEncryptor(byte[] sb) {
      this.secretBytes = sb;
    }
    
    public byte [] encryptBytes(byte[] data) throws Exception {
      String algo = dhSKAlgo;
      return GMSEncrypt.encryptBytes(data, getEncryptCipher(algo));
    }

    private Cipher getEncryptCipher(String dhSKAlgo)
      throws Exception{
      try {
        if(encrypt == null) {
          synchronized (this) {
            if(encrypt == null)
              encrypt = GMSEncrypt.getEncryptCipher(dhSKAlgo, secretBytes);
          }          
        }
      }catch(Exception ex) {
        throw ex;
      }
      return encrypt;
    }
    
    public byte[] decryptBytes(byte[] data) throws Exception
    {
      String algo = dhSKAlgo;
      Cipher c = getDecryptCipher(algo);
      return GMSEncrypt.decryptBytes(data, c);
    }

    private Cipher getDecryptCipher( String dhSKAlgo)
      throws Exception{
      if(decrypt == null) {
        synchronized (this) {
          if(decrypt == null)
            decrypt = GMSEncrypt.getDecryptCipher(dhSKAlgo, secretBytes);
        }        
      }
      return decrypt;
    }
  }
}

