import Foundation
import AVFoundation
import UniformTypeIdentifiers

/// Bounded, cancellable ranges. Cloud URLs never contain API bearer credentials.
final class RemoteResourceLoader:NSObject,AVAssetResourceLoaderDelegate {
    let queue=DispatchQueue(label:"io.localplayer.app.range-loader")
    private let source:DAVSource?
    private let password:String
    private let url:URL
    private let renew:(@Sendable () async throws -> URL)?
    private var work=[ObjectIdentifier:RangeWork]()
    init(source:DAVSource,password:String,url:URL){self.source=source;self.password=password;self.url=url;renew=nil}
    init(url:URL,renew:@escaping @Sendable () async throws -> URL){source=nil;password="";self.url=url;self.renew=renew}
    func asset()->AVURLAsset {
        var parts=URLComponents(url:url,resolvingAgainstBaseURL:false)!;parts.scheme="yingxi-dav"
        let asset=AVURLAsset(url:parts.url!);asset.resourceLoader.setDelegate(self,queue:queue);return asset
    }
    func resourceLoader(_ resourceLoader:AVAssetResourceLoader,shouldWaitForLoadingOfRequestedResource request:AVAssetResourceLoadingRequest)->Bool {
        let key=ObjectIdentifier(request)
        let transfer=RangeWork(request:request,source:source,password:password,url:url,queue:queue,renew:renew){[weak self] in self?.work.removeValue(forKey:key)}
        work[key]=transfer;transfer.next();return true
    }
    func resourceLoader(_ resourceLoader:AVAssetResourceLoader,didCancel request:AVAssetResourceLoadingRequest){work.removeValue(forKey:ObjectIdentifier(request))?.cancel()}
    func close(){queue.async{[self] in work.values.forEach{$0.cancel()};work.removeAll()}}
    deinit{work.values.forEach{$0.cancel()}}
}

private final class RangeWork:NSObject,URLSessionDataDelegate,@unchecked Sendable {
    private let request:AVAssetResourceLoadingRequest
    private let source:DAVSource?
    private let password:String
    private let queue:DispatchQueue
    private let done:()->Void
    private var url:URL
    private let renew:(@Sendable () async throws -> URL)?
    private var refreshing=false,renewed=false,accepted=false,closed=false
    private var renewal:Task<Void,Never>?
    private var session:URLSession?
    private var sessionIdentity:ObjectIdentifier?
    private var task:URLSessionDataTask?
    private var progress:RangeProgress
    private var validator:String?
    init(request:AVAssetResourceLoadingRequest,source:DAVSource?,password:String,url:URL,queue:DispatchQueue,renew:(@Sendable () async throws -> URL)?,done:@escaping()->Void){
        self.request=request;self.source=source;self.password=password;self.url=url;self.queue=queue;self.renew=renew;self.done=done
        let data=request.dataRequest
        let offset=max(data?.requestedOffset ?? 0,data?.currentOffset ?? 0)
        let length=max(0,Int64(data?.requestedLength ?? 1)-(offset-(data?.requestedOffset ?? 0)))
        progress=RangeProgress(offset:offset,length:length,allToEnd:data?.requestsAllDataToEndOfResource==true)
    }
    func next(){
        guard !closed else{return}
        guard let length=progress.nextLength else{finish();return}
        do {
            var req:URLRequest
            if let source{req=try source.request(url:url,password:password)}else{
                guard url.scheme=="https",url.host != nil,url.user==nil,url.password==nil else{throw DAVError.invalidURL}
                req=URLRequest(url:url);req.timeoutInterval=30;req.cachePolicy = .reloadIgnoringLocalCacheData
            }
            guard progress.offset<=Int64.max-length else{throw DAVError.invalidRange}
            req.setValue("bytes=\(progress.offset)-\(progress.offset+length-1)",forHTTPHeaderField:"Range")
            req.setValue("identity",forHTTPHeaderField:"Accept-Encoding")
            if let validator,validator.hasPrefix("\""){req.setValue(validator,forHTTPHeaderField:"If-Range")}
            if session==nil {
                let config=URLSessionConfiguration.ephemeral;config.httpCookieStorage=nil;config.urlCredentialStorage=nil
                let callbacks=OperationQueue();callbacks.maxConcurrentOperationCount=1;callbacks.underlyingQueue=queue
                session=URLSession(configuration:config,delegate:self,delegateQueue:callbacks)
            }
            accepted=false
            if let session {
                assert(sessionIdentity==nil || sessionIdentity==ObjectIdentifier(session),"A range transfer must reuse its HTTP session")
                sessionIdentity=ObjectIdentifier(session)
            }
            task=session?.dataTask(with:req);task?.resume()
        }catch{finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive response:URLResponse,completionHandler:@escaping(URLSession.ResponseDisposition)->Void){
        guard !closed,let http=response as? HTTPURLResponse else{completionHandler(.cancel);return}
        if http.statusCode==403,renew != nil,!renewed{refreshing=true;renewed=true;completionHandler(.cancel);return}
        do {
            let current=http.value(forHTTPHeaderField:"ETag") ?? http.value(forHTTPHeaderField:"Last-Modified")
            if let validator,current != validator{throw DAVError.invalidRange};if validator==nil{validator=current}
            try progress.accept(status:http.statusCode,range:http.value(forHTTPHeaderField:"Content-Range"),contentLength:response.expectedContentLength,encoding:http.value(forHTTPHeaderField:"Content-Encoding"),metadataOnly:request.dataRequest==nil)
            if let info=request.contentInformationRequest{info.contentType=UTType(mimeType:response.mimeType ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier;info.contentLength=progress.total ?? 0;info.isByteRangeAccessSupported=http.statusCode==206}
            accepted=true
            if request.dataRequest==nil{completionHandler(.cancel);finish()}else{completionHandler(.allow)}
        }catch{completionHandler(.cancel);finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive data:Data){
        guard !closed,accepted else{return}
        do{try progress.receive(Int64(data.count));request.dataRequest?.respond(with:data);if progress.nextLength==nil{finish()}}catch{finish(error)}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){
        guard !closed else{return};self.task=nil
        if refreshing,let renew{
            refreshing=false
            renewal=Task{do{let updated=try await renew();try Task.checkCancellation();self.queue.async{guard !self.closed else{return};self.url=updated;self.next()}}catch{self.queue.async{self.finish(error)}}}
            return
        }
        if let error{finish(error);return}
        do{try progress.completeChunk();renewed=false;next()}catch{finish(error)}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){
        guard let target=request.url,target.scheme=="https",target.user==nil,target.password==nil else{completionHandler(nil);return}
        var next=request
        if source==nil || !DAVSource.sameOrigin(source!.baseURL,target){next.setValue(nil,forHTTPHeaderField:"Authorization");next.setValue(nil,forHTTPHeaderField:"Cookie")}
        completionHandler(next)
    }
    func cancel(){closed=true;renewal?.cancel();task?.cancel();session?.invalidateAndCancel();task=nil;session=nil}
    private func finish(_ error:Error?=nil){guard !closed else{return};cancel();if let error{request.finishLoading(with:error)}else{request.finishLoading()};done()}
}
