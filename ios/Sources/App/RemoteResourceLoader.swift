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
    private var offset:Int64,remaining:Int64,total:Int64=0,chunkExpected:Int64=0,chunkReceived:Int64=0
    private var closed=false
    init(request:AVAssetResourceLoadingRequest,source:DAVSource,password:String,url:URL,queue:DispatchQueue,done:@escaping()->Void){self.request=request;self.source=source;self.password=password;self.url=url;self.queue=queue;self.done=done;let data=request.dataRequest;offset=max(data?.requestedOffset ?? 0,data?.currentOffset ?? 0);remaining=max(0,Int64(data?.requestedLength ?? 1)-(offset-(data?.requestedOffset ?? 0)))}
    func next(){
        guard !closed else{return}
        if remaining<=0 || (total>0 && offset>=total){finish();return}
        do {
            var req=try source.request(url:url,password:password)
            let length=min(remaining,1024*1024);guard offset<=Int64.max-length else{throw DAVError.invalidRange}
            req.setValue("bytes=\(offset)-\(offset+length-1)",forHTTPHeaderField:"Range")
            chunkExpected=length;chunkReceived=0
            let config=URLSessionConfiguration.ephemeral;config.httpCookieStorage=nil;config.urlCredentialStorage=nil
            let callbacks=OperationQueue();callbacks.maxConcurrentOperationCount=1;callbacks.underlyingQueue=queue
            session=URLSession(configuration:config,delegate:self,delegateQueue:callbacks);task=session?.dataTask(with:req);task?.resume()
        }catch{finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive response:URLResponse,completionHandler:@escaping(URLSession.ResponseDisposition)->Void){
        guard !closed,let http=response as? HTTPURLResponse else{completionHandler(.cancel);return}
        do {
            if http.statusCode==206 {
                let range=try ByteRange.parse(http.value(forHTTPHeaderField:"Content-Range") ?? "")
                guard range.start==offset,range.end-range.start+1<=chunkExpected else{throw DAVError.invalidRange}
                total=range.total;chunkExpected=range.end-range.start+1
            }else if http.statusCode==200 && offset==0 && response.expectedContentLength>0 {
                total=response.expectedContentLength;chunkExpected=min(chunkExpected,total)
                if request.dataRequest != nil && total>chunkExpected {throw DAVError.rangeUnsupported}
            }else if http.statusCode==200 {throw DAVError.rangeUnsupported}
            else{throw DAVError.rejectedResponse(http.statusCode)}
            if let info=request.contentInformationRequest {info.contentType=UTType(mimeType:response.mimeType ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier;info.contentLength=total;info.isByteRangeAccessSupported=http.statusCode==206}
            if request.dataRequest==nil {completionHandler(.cancel);finish();return}
            if request.dataRequest?.requestsAllDataToEndOfResource==true {remaining=total-offset}
            completionHandler(.allow)
        }catch{completionHandler(.cancel);finish(error)}
    }
    func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive data:Data){
        guard !closed else{return};let count=Int(min(Int64(data.count),min(remaining,chunkExpected-chunkReceived)))
        if count>0 {request.dataRequest?.respond(with:data.prefix(count));offset+=Int64(count);remaining-=Int64(count);chunkReceived+=Int64(count)}
        if remaining==0 || offset>=total {finish()}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){
        guard !closed else{return};session.finishTasksAndInvalidate();self.session=nil;self.task=nil
        if let error{finish(error)}else if chunkReceived!=chunkExpected{finish(DAVError.invalidRange)}else{next()}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){
        guard let target=request.url,target.scheme=="https",target.user==nil,target.password==nil else{completionHandler(nil);return}
        var next=request;if !DAVSource.sameOrigin(source.baseURL,target){next.setValue(nil,forHTTPHeaderField:"Authorization");next.setValue(nil,forHTTPHeaderField:"Cookie")};completionHandler(next)
    }
    private func finish(_ error:Error?=nil){guard !closed else{return};closed=true;session?.invalidateAndCancel();session=nil;task=nil;if let error{request.finishLoading(with:error)}else{request.finishLoading()};done()}
    func cancel(){guard !closed else{return};closed=true;session?.invalidateAndCancel();session=nil;task=nil}
}
