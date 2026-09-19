package com.com11h.partner
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
class SecureSession(context:Context){
 private val prefs:SharedPreferences=context.getSharedPreferences("partner_session",Context.MODE_PRIVATE); private val alias="com11h_partner_session_key"
 private fun key():SecretKey{val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(ks.getKey(alias,null)as?SecretKey)?.let{return it};val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build());return kg.generateKey()}
 fun save(token:String,kcn:Int,name:String,storeId:Int,category:String=""){val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())};prefs.edit().putString("token",Base64.encodeToString(c.doFinal(token.toByteArray()),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(c.iv,Base64.NO_WRAP)).putInt("kcn",kcn).putString("name",name).putInt("store",storeId).putString("category",category).apply()}
 fun token():String?=runCatching{val enc=prefs.getString("token",null)?:return null;val iv=Base64.decode(prefs.getString("iv",null),Base64.NO_WRAP);val c=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))};String(c.doFinal(Base64.decode(enc,Base64.NO_WRAP)))}.getOrNull()
 fun kcnId():Int?=prefs.getInt("kcn",0).takeIf{it>0};fun name():String?=prefs.getString("name",null);fun storeId():Int=prefs.getInt("store",0);fun category():String=prefs.getString("category","")?:"";fun clear(){prefs.edit().clear().apply()}
}
