import Foundation
import AVFoundation
import UniformTypeIdentifiers

/// Bounded, cancellable byte ranges feed AVFoundation without exposing credentials in media URLs.
final class RemoteResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    let queue=DispatchQueue(label:"io.localplayer.app.range-loader")
    private let source:DAVSource
    private let password:String
    private let url:URL
    private var work=[ObjectIdentifier:RangeWork]()
    init(source:DAVSource,password:String,url:URL){self.source=source;self.password=password;self.url=url}
    func asset() -> AVURLAsset {var components=URLComponents(url:url,resolvingAgainstBaseURL:false)!;components.scheme="yingxi-dav";let asset=AVURLAsset(url:components.url!);asset.resourceLoader.setDelegate(self,queue:queue);return asset}
    func resourceLoader(_ resourceLoader:AVAssetResourceLoader,shouldWaitForLoadingOfRequestedResource request:AVAssetResourceLoadingRequest)->Bool {
        let key=ObjectIdentifier(request)
        let transfer=RangeWork(request:request,source:source,password:password,url:url,queue:queue){[weak self] in self?.work.removeValue(forKey:key)}
        work[key]=transfer;transfer.next();return true
    }
    func resourceLoader(_ resourceLoader:AVAssetResourceLoader,didCancel request:AVAssetResourceLoadingRequest){let key=ObjectIdentifier(request);work.removeValue(forKey:key)?.cancel()}
    func close(){queue.async{[self] in work.values.forEach{$0.cancel()};work.removeAll()}}
    deinit{work.values.forEach{$0.cancel()}}
}
private final class RangeWork:NSObject,URLSessionDataDelegate, @unchecked Sendable {
    private let request:AVAssetResourceLoadingRequest
    private let source:DAVSource,password:String,url:URL,queue:DispatchQueue,done:()->Void
    private var session:URLSession?
    private var task:URLSessionDataTask?
    private var progress:RangeProgress
    private var validator:String?
    private var closed=false
    init(request:AVAssetResourceLoadingRequest,source:DAVSource,password:String,url:URL,queue:DispatchQueue,done:@escaping()->Void){
        self.request=request;self.source=source;self.password=password;self.url=url;self.queue=queue;self.done=done
        let data=request.dataRequest
        let offset=max(data?.requestedOffset ?? 0,data?.currentOffset ?? 0)
        let length=max(0,Int64(data?.requestedLength ?? 1)-(offset-(data?.requestedOffset ?? 0)))
        progress=RangeProgress(offset:offset,length:length,allToEnd:data?.requestsAllDataToEndOfResource==true)
    }
    func next(){
        guard !closed else{return}
        guard let length=progress.nextLength else{finish();return}
        do {
            var req=try source.request(url:url,password:password)
            guard progress.offset<=Int64.max-length else{throw DAVError.invalidRange}
            req.setValue("bytes=\(progress.offset)-\(progress.offset+length-1)",forHTTPHeaderField:"Range")
            req.setValue("identity",forHTTPHeaderField:"Accept-Encoding")
            if let validator{req.setValue(validator,forHTTPHeaderField:"If-Range")}
            let config=URLSessionConfiguration.ephemeral;config.httpCookieStorage=nil;config.urlCredentialStorage=nil
            let callbacks=OperationQueue();callbacks.maxConcurrentOperationCount=1;callbacks.underlyingQueue=queue
            session=URLSession(configuration:config,delegate:self,delegateQueue:callbacks);task=session?.dataTask(with:req);task?.resume()
        }catch{finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive response:URLResponse,completionHandler:@escaping(URLSession.ResponseDisposition)->Void){
        guard !closed,let http=response as? HTTPURLResponse else{completionHandler(.cancel);return}
        do {
            let currentValidator=http.value(forHTTPHeaderField:"ETag") ?? http.value(forHTTPHeaderField:"Last-Modified")
            if let validator,let currentValidator,validator != currentValidator {throw DAVError.invalidRange}
            try progress.accept(status:http.statusCode,range:http.value(forHTTPHeaderField:"Content-Range"),contentLength:response.expectedContentLength,encoding:http.value(forHTTPHeaderField:"Content-Encoding"),metadataOnly:request.dataRequest==nil)
            if validator==nil {validator=currentValidator}
            if let info=request.contentInformationRequest {info.contentType=UTType(mimeType:response.mimeType ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier;info.contentLength=progress.total ?? 0;info.isByteRangeAccessSupported=http.statusCode==206}
            if request.dataRequest==nil {completionHandler(.cancel);finish();return}
            completionHandler(.allow)
        }catch{completionHandler(.cancel);finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive data:Data){
        guard !closed else{return}
        do {try progress.receive(Int64(data.count));request.dataRequest?.respond(with:data);if progress.nextLength==nil {finish()}}
        catch{finish(error)}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){
        guard !closed else{return};session.finishTasksAndInvalidate();self.session=nil;self.task=nil
        if let error{finish(error);return}
        do {try progress.completeChunk();next()}catch{finish(error)}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){
        guard let target=request.url,target.scheme=="https",target.user==nil,target.password==nil else{completionHandler(nil);return}
        var next=request;if !DAVSource.sameOrigin(source.baseURL,target){next.setValue(nil,forHTTPHeaderField:"Authorization");next.setValue(nil,forHTTPHeaderField:"Cookie")};completionHandler(next)
    }
    private func finish(_ error:Error?=nil){guard !closed else{return};closed=true;session?.invalidateAndCancel();session=nil;task=nil;if let error{request.finishLoading(with:error)}else{request.finishLoading()};done()}
    func cancel(){guard !closed else{return};closed=true;session?.invalidateAndCancel();session=nil;task=nil}
}
