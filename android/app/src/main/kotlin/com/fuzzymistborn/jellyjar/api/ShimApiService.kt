package com.fuzzymistborn.jellyjar.api

import com.fuzzymistborn.jellyjar.model.TranscodeJob
import com.fuzzymistborn.jellyjar.model.TranscodeRequest
import okhttp3.ResponseBody
import retrofit2.http.*

interface ShimApiService {

    @POST("transcode")
    suspend fun startTranscode(@Body request: TranscodeRequest): TranscodeJob

    @GET("jobs/{jobId}")
    suspend fun getJob(@Path("jobId") jobId: String): TranscodeJob

    @GET("jobs")
    suspend fun listJobs(): List<TranscodeJob>

    @DELETE("jobs/{jobId}")
    suspend fun deleteJob(@Path("jobId") jobId: String)

    @GET("download/{jobId}")
    @Streaming
    suspend fun downloadFile(@Path("jobId") jobId: String): ResponseBody

    @GET("health")
    suspend fun health(): Map<String, String>

    @GET("presets")
    suspend fun getPresets(): List<String>
}
