package com.dreemurr.shinsekai.voice

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming
import retrofit2.http.Url

// 云 TTS 简单请求：JSON POST 与 multipart 上传，路径由调用方按服务商拼接。
interface TtsApi {
    @POST
    fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Call<ResponseBody>

    // 阿里合成以 SSE 流式返回音频分片，@Streaming 避免响应体整体缓冲进内存。
    @Streaming
    @POST
    fun postStream(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Call<ResponseBody>

    // MiniMax 音频上传：purpose 表单字段 + 文件分片。
    @Multipart
    @POST
    fun upload(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Part("purpose") purpose: RequestBody,
        @Part file: MultipartBody.Part,
    ): Call<ResponseBody>
}
