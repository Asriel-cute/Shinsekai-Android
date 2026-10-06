package com.dreemurr.shinsekai.chat

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ChatApi {
    // 返回 Call，允许主动取消流式连接。
    @Streaming
    @POST
    fun streamChat(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ResponsesRequest
    ): Call<ResponseBody>
}
