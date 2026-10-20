package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.backup.ArchiveFormat
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class FolderHttpReply(val status:Int,val body:JSONObject)
interface TeamFolderTransport {
    suspend fun request(session:TeamSession,body:JSONObject?=null):FolderHttpReply
}

class TeamFolderClient @Inject constructor(private val gateway:TeamAccountGateway):TeamFolderTransport {
    override suspend fun request(session:TeamSession,body:JSONObject?):FolderHttpReply {
        if(session.offline)throw OfflineUnavailable("文件夹已保存在本机，请联网核验后同步")
        require(session.deviceId.isEmpty() || session.deviceId==gateway.device)
        val connection=URL(accountServer(session.server)+"/v1/personal-folders").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=8000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer ${session.token}")
            connection.setRequestProperty("X-Score-Team",session.principal.teamId)
            connection.setRequestProperty("X-Device-Id",gateway.device);connection.setRequestProperty("Accept","application/json")
            if(body!=null) {
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size<=FolderPayload.LIMIT+16384)
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { out -> var offset=0;while(offset<bytes.size) {
                    coroutineContext.ensureActive();val count=minOf(16384,bytes.size-offset);out.write(bytes,offset,count);offset+=count
                } }
            }
            coroutineContext.ensureActive();val status=connection.responseCode
            if(status in 300..399)throw AccountRejected(status,"redirect_rejected")
            require(connection.contentType?.substringBefore(';')?.trim()=="application/json") { "文件夹服务响应格式不匹配" }
            val stream=if(status==200)connection.inputStream else connection.errorStream
            val output=ByteArrayOutputStream();val limit=if(status==200)17*1024*1024 else 65536
            stream?.use { input -> val buffer=ByteArray(16384);while(true) {
                coroutineContext.ensureActive();val count=input.read(buffer);if(count<0)break
                require(output.size()+count<=limit) { "文件夹响应过大" };output.write(buffer,0,count)
            } }
            val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            ArchiveFormat.depthCheck(text);val row=JSONObject(text)
            if(status!=200 && !(status==409 && row.optString("error")=="folder_conflict"))throw AccountRejected(status,row.optString("error"))
            return FolderHttpReply(status,row)
        } finally { connection.disconnect() }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class TeamFolderModule {
    @dagger.Binds abstract fun transport(client:TeamFolderClient):TeamFolderTransport
}
