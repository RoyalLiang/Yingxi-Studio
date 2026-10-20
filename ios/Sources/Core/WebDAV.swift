import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

public enum DAVError: LocalizedError {
    case invalidURL, rejectedResponse(Int), invalidListing, outsideSource, rangeUnsupported, invalidRange
    public var errorDescription: String? {
        switch self {
        case .invalidURL: return "请使用不含账号、查询参数的 HTTPS WebDAV 地址。"
        case .rejectedResponse(let status): return "服务器返回 HTTP \(status)，请检查地址和账号权限。"
        case .invalidListing: return "服务器没有返回有效的 WebDAV 目录。"
        case .outsideSource: return "已阻止访问来源之外的地址。"
        case .rangeUnsupported: return "服务器不支持本播放器所需的 Range 随机读取。"
        case .invalidRange: return "服务器返回的文件范围无效。"
        }
    }
}
public struct DAVSource: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var name: String
    public var baseURL: URL
    public var username: String
    public init(id: UUID = UUID(), name: String, address: String, username: String) throws {
        guard let url=URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)), let c=URLComponents(url:url,resolvingAgainstBaseURL:false), c.scheme=="https", c.host != nil, c.user==nil, c.password==nil, c.query==nil, c.fragment==nil else {throw DAVError.invalidURL}
        self.id=id;self.name=name.isEmpty ? (url.host ?? "WebDAV") : name;self.baseURL=url.hasDirectoryPath ? url : url.appendingPathComponent("");self.username=username
    }
    public func contains(_ url: URL) -> Bool {
        guard Self.sameOrigin(baseURL,url) else{return false}
        let root=baseURL.standardized.path
        let path=url.standardized.path
        guard !path.split(separator:"/").contains(".."), !path.contains("\0") else{return false}
        return path==root || path.hasPrefix(root.hasSuffix("/") ? root : root+"/")
    }
    public static func sameOrigin(_ a: URL,_ b: URL) -> Bool {a.scheme?.lowercased()==b.scheme?.lowercased() && a.host?.lowercased()==b.host?.lowercased() && (a.port ?? 443)==(b.port ?? 443)}
    public func request(url: URL, password: String, method: String = "GET") throws -> URLRequest {
        guard contains(url), url.user==nil, url.password==nil, url.query==nil, url.fragment==nil else{throw DAVError.outsideSource}
        var request=URLRequest(url:url);request.httpMethod=method;request.timeoutInterval=20;request.cachePolicy = .reloadIgnoringLocalCacheData
        if !username.isEmpty || !password.isEmpty {request.setValue("Basic "+Data((username+":"+password).utf8).base64EncodedString(),forHTTPHeaderField:"Authorization")}
        return request
    }
}
public struct DAVEntry: Identifiable, Equatable, Sendable {
    public let url: URL
    public let name: String
    public let directory: Bool
    public let size: Int64
    public var id: String {url.absoluteString}
    public var isVideo: Bool {["mp4","m4v","mov","mkv","webm","avi","ts"].contains(url.pathExtension.lowercased())}
}
public final class DAVListing: NSObject, XMLParserDelegate {
    private let source: DAVSource
    private let directory: URL
    private var entries=[DAVEntry]()
    private var text="",href="",name="",responseStatus="",propStatus="",propName=""
    private var size:Int64=0,propSize:Int64=0,depth=0
    private var isDirectory=false,propDirectory=false,inResponse=false,inProps=false,readable=false,rootValid=false
    public init(source: DAVSource, directory: URL) {self.source=source;self.directory=directory}
    public func parse(_ data: Data) throws -> [DAVEntry] {
        guard data.count<=4*1024*1024 else{throw DAVError.invalidListing}
        let parser=XMLParser(data:data);parser.shouldProcessNamespaces=true;parser.shouldResolveExternalEntities=false;parser.delegate=self
        guard parser.parse(),rootValid,entries.count<=10000 else{throw DAVError.invalidListing}
        return entries.sorted {a,b in if a.directory != b.directory{return a.directory};let order=a.name.localizedStandardCompare(b.name);return order == .orderedSame ? a.url.absoluteString < b.url.absoluteString : order == .orderedAscending}
    }
    public func parser(_ parser: XMLParser,didStartElement elementName: String,namespaceURI: String?,qualifiedName: String?,attributes: [String:String]) {
        let element=elementName.lowercased();text=""
        if depth==0 {rootValid=element=="multistatus" && namespaceURI=="DAV:";if !rootValid{parser.abortParsing()}}
        depth+=1
        if element=="response" {inResponse=true;href="";name="";responseStatus="";size=0;isDirectory=false;readable=false}
        if element=="propstat" {inProps=true;propStatus="";propName="";propSize=0;propDirectory=false}
        if inProps && element=="collection" {propDirectory=true}
    }
    public func parser(_ parser: XMLParser,foundCharacters string: String){text+=string}
    public func parser(_ parser: XMLParser,foundInternalEntityDeclarationWithName name:String,value:String?){parser.abortParsing()}
    public func parser(_ parser: XMLParser,foundExternalEntityDeclarationWithName name:String,publicID:String?,systemID:String?){parser.abortParsing()}
    public func parser(_ parser: XMLParser,didEndElement elementName: String,namespaceURI: String?,qualifiedName: String?) {
        defer{depth-=1;text=""}
        let element=elementName.lowercased()
        if inResponse {
            switch element {
            case "href": if !inProps{href=text.trimmingCharacters(in:.whitespacesAndNewlines)}
            case "displayname": if inProps{propName=text}
            case "getcontentlength": if inProps{propSize=Int64(text.trimmingCharacters(in:.whitespacesAndNewlines)) ?? 0}
            case "status": if inProps{propStatus=text}else{responseStatus=text}
            case "propstat":
                if propStatus.contains(" 200 "){readable=true;if !propName.isEmpty{name=propName};size=max(size,propSize);isDirectory=isDirectory || propDirectory}
                inProps=false
            case "response":
                defer{inResponse=false}
                guard readable,responseStatus.isEmpty || responseStatus.contains(" 200 "),let url=URL(string:href,relativeTo:directory)?.absoluteURL,source.contains(url),url.user==nil,url.password==nil,url.query==nil,url.fragment==nil else{return}
                guard url.standardized.path.trimmingCharacters(in:CharacterSet(charactersIn:"/")) != directory.standardized.path.trimmingCharacters(in:CharacterSet(charactersIn:"/")) else{return}
                entries.append(DAVEntry(url:url,name:name.isEmpty ? url.lastPathComponent : name,directory:isDirectory,size:max(0,size)))
                if entries.count>10000 {parser.abortParsing()}
            default: break
            }
        }
    }
}
public struct ByteRange: Equatable {
    public let start: Int64
    public let end: Int64
    public let total: Int64
    public static func parse(_ value: String) throws -> ByteRange {
        let pattern=#"^bytes (\d+)-(\d+)/(\d+)$"#
        guard let match=value.range(of:pattern,options:.regularExpression) else{throw DAVError.invalidRange}
        let parts=value[match].dropFirst(6).split(whereSeparator:{$0=="-" || $0=="/"})
        guard parts.count==3,let a=Int64(parts[0]),let b=Int64(parts[1]),let total=Int64(parts[2]),a>=0,b>=a,total>b else{throw DAVError.invalidRange}
        return ByteRange(start:a,end:b,total:total)
    }
}
/// Credential and cookie forwarding stops at the original HTTPS origin.
public final class DAVRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let origin: URL
    private let browsing: Bool
    public init(origin: URL,browsing: Bool){self.origin=origin;self.browsing=browsing}
    public func urlSession(_ session: URLSession,task: URLSessionTask,willPerformHTTPRedirection response: HTTPURLResponse,newRequest request: URLRequest,completionHandler: @escaping (URLRequest?)->Void) {
        guard let url=request.url,url.scheme=="https",url.user==nil,url.password==nil else{completionHandler(nil);return}
        if !DAVSource.sameOrigin(origin,url) {
            if browsing {completionHandler(nil);return}
            var clean=request;clean.setValue(nil,forHTTPHeaderField:"Authorization");clean.setValue(nil,forHTTPHeaderField:"Cookie");completionHandler(clean)
        }else{completionHandler(request)}
    }
}
public final class DAVClient {
    private let source: DAVSource
    private let password: String
    public init(source: DAVSource,password: String){self.source=source;self.password=password}
    public func list(_ url: URL) async throws -> [DAVEntry] {
        var request=try source.request(url:url,password:password,method:"PROPFIND");request.setValue("1",forHTTPHeaderField:"Depth");request.setValue("application/xml; charset=utf-8",forHTTPHeaderField:"Content-Type")
        request.httpBody=Data(#"<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"#.utf8)
        let config=URLSessionConfiguration.ephemeral;config.httpCookieStorage=nil;config.urlCredentialStorage=nil
        let session=URLSession(configuration:config,delegate:DAVRedirectPolicy(origin:source.baseURL,browsing:true),delegateQueue:nil);defer{session.invalidateAndCancel()}
        let (bytes,response)=try await session.bytes(for:request)
        guard let http=response as? HTTPURLResponse,http.statusCode==207 || http.statusCode==200 else{throw DAVError.rejectedResponse((response as? HTTPURLResponse)?.statusCode ?? 0)}
        var data=Data()
        for try await byte in bytes {data.append(byte);if data.count>4*1024*1024 {throw DAVError.invalidListing}}
        return try DAVListing(source:source,directory:url).parse(data)
    }
}
