package com.dreemurr.shinsekai.chat

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ChatApi {
    // 流式对话：返回 Call 允许取消；请求体由 ProviderSpec 按提供商序列化。
    @Streaming
    @POST
    fun postStream(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Call<ResponseBody>

    // 非流式请求，用于连通性测试。
    @POST
    fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Call<ResponseBody>

    @GET
    fun getModels(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
    ): Call<ResponseBody>
}
