package com.com11h.partner
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
class SyncWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){override suspend fun doWork():Result{val s=SecureSession(applicationContext);val t=s.token()?:return Result.success();val k=s.kcnId()?:return Result.success();return try{Api(BuildConfig.API_BASE_URL,k,t).call("partner_pending_pickups");Result.success()}catch(_:UnauthorizedException){Result.success()}catch(_:Exception){Result.retry()}}}
