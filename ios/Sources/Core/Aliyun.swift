import Foundation
import CryptoKit
import Security

public enum AliyunConfiguration {
    public static let clientID = "53a4ba437a8146819b5ebf05235e2762"
    public static let scope = "user:base,file:all:read"
    public static let callbackScheme = "smartdrive" + clientID
}
public enum CloudError: LocalizedError {
    case invalidResponse, unauthorized, cancelled, notInstalled, invalidCallback
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "阿里云盘响应无效，请稍后重试。"
        case .unauthorized: return "阿里云盘授权已失效，请重新授权。"
        case .cancelled: return "授权已取消或超时。"
        case .notInstalled: return "未安装阿里云盘，请先安装官方 App。"
        case .invalidCallback: return "授权回跳与当前会话不匹配，请重试。"
        }
    }
}
public struct AliyunPKCE: CustomStringConvertible, CustomDebugStringConvertible, Sendable {
    public let verifier: String
    public let state: String
    public var challenge: String { Self.encode(Data(SHA256.hash(data: Data(verifier.utf8)))) }
    public var description: String { "AliyunPKCE([redacted])" }
    public var debugDescription: String { description }
    public init() throws {
        func random() throws -> String {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw CloudError.invalidResponse }
            return Self.encode(Data(bytes))
        }
        verifier = try random(); state = try random()
    }
    private static func encode(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
}
public struct AliyunToken: Codable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let access: String
    public let refresh: String?
    public let expires: Date
    public var description: String { "AliyunToken([redacted])" }
    public var debugDescription: String { description }
}
public struct AliyunSource: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public let userID: String
    public let driveID: String
    public let name: String
    public init(id: UUID = UUID(), userID: String, driveID: String, name: String) { self.id=id;self.userID=userID;self.driveID=driveID;self.name=name }
}
public struct AliyunFile: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let directory: Bool
    public let size: Int64
    public let created: Date?
    public var isVideo: Bool { !directory && ["mp4","m4v","mov","mkv","webm","avi","ts","m2ts","mpg","mpeg","wmv","flv","3gp"].contains((name as NSString).pathExtension.lowercased()) }
}
private final class CloudNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
public final class AliyunAPI: @unchecked Sendable {
    private let base: URL
    private let session: URLSession
    public convenience init() { self.init(base: URL(string:"https://openapi.alipan.com/")!, configuration: .ephemeral) }
    // Internal injection seam for controlled URLProtocol tests; production API origin is fixed above.
    init(base: URL, configuration: URLSessionConfiguration) {
        self.base=base;configuration.httpCookieStorage=nil;configuration.urlCredentialStorage=nil
        session=URLSession(configuration:configuration,delegate:CloudNoRedirect(),delegateQueue:nil)
    }
    deinit { session.invalidateAndCancel() }
    private func execute(_ request: URLRequest) async throws -> [String:Any] {
        try Task.checkCancellation()
        let (bytes,response)=try await session.bytes(for:request)
        guard let http=response as? HTTPURLResponse else { throw CloudError.invalidResponse }
        if http.statusCode == 401 { throw CloudError.unauthorized }
        guard (200..<300).contains(http.statusCode), response.expectedContentLength <= 4*1024*1024 else { throw CloudError.invalidResponse }
        var data=Data()
        for try await byte in bytes { if data.count >= 4*1024*1024 { throw CloudError.invalidResponse };data.append(byte) }
        guard let result=try JSONSerialization.jsonObject(with:data) as? [String:Any] else { throw CloudError.invalidResponse }
        return result
    }
    private func post(_ path: String, _ body: [String:Any], token: String? = nil) async throws -> [String:Any] {
        var request=URLRequest(url:base.appendingPathComponent(path));request.httpMethod="POST";request.timeoutInterval=30
        request.setValue("application/json",forHTTPHeaderField:"Content-Type")
        if let token { request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization") }
        request.httpBody=try JSONSerialization.data(withJSONObject:body)
        return try await execute(request)
    }
    public func authorization(_ grant: AliyunPKCE) async throws -> URL {
        var parts=URLComponents(url:base.appendingPathComponent("oauth/authorize"),resolvingAgainstBaseURL:false)!
        parts.queryItems=["client_id":AliyunConfiguration.clientID,"redirect_uri":"oob","scope":AliyunConfiguration.scope,"response_type":"code","bundle_id":"io.localplayer.app","source":"appLink","code_challenge":grant.challenge,"code_challenge_method":"S256","state":grant.state].map{URLQueryItem(name:$0.key,value:$0.value)}
        let result=try await execute(URLRequest(url:parts.url!))
        guard let value=result["redirectUri"] as? String, let url=URL(string:value),url.scheme=="smartdrive" else { throw CloudError.invalidResponse }
        return url
    }
    public func exchange(code: String, verifier: String) async throws -> AliyunToken {
        try await token(["grant_type":"authorization_code","code":code,"code_verifier":verifier])
    }
    public func refresh(_ value: String) async throws -> AliyunToken { try await token(["grant_type":"refresh_token","refresh_token":value]) }
    private func token(_ body: [String:String]) async throws -> AliyunToken {
        var request=body;request["client_id"]=AliyunConfiguration.clientID
        let result=try await post("oauth/access_token",request)
        guard let access=result["access_token"] as? String,!access.isEmpty,let seconds=result["expires_in"] as? Double,seconds>0,seconds<=31_536_000 else { throw CloudError.invalidResponse }
        return AliyunToken(access:access,refresh:(result["refresh_token"] as? String).flatMap{$0.isEmpty ? nil : $0},expires:Date().addingTimeInterval(seconds))
    }
    public func drive(_ access: String) async throws -> (user: String, drive: String, name: String) {
        let result=try await post("adrive/v1.0/user/getDriveInfo",[:],token:access)
        guard let user=result["user_id"] as? String,!user.isEmpty,let drive=result["default_drive_id"] as? String,!drive.isEmpty else { throw CloudError.invalidResponse }
        return (user,drive,result["name"] as? String ?? "阿里云盘")
    }
    public func list(_ access: String, drive: String, parent: String) async throws -> [AliyunFile] {
        var marker="",seen=Set<String>(),items=[AliyunFile]()
        repeat {
            let result=try await post("adrive/v1.0/openFile/list",["drive_id":drive,"parent_file_id":parent,"marker":marker,"limit":100,"order_by":"name","order_direction":"ASC"],token:access)
            guard let rows=result["items"] as? [[String:Any]] else { throw CloudError.invalidResponse }
            items += try rows.map(Self.file)
            guard items.count<=10000 else { throw CloudError.invalidResponse }
            marker=result["next_marker"] as? String ?? ""
            if !marker.isEmpty && !seen.insert(marker).inserted { throw CloudError.invalidResponse }
        } while !marker.isEmpty
        return items.sorted { a,b in
            if a.directory != b.directory { return a.directory }
            let order=a.name.localizedStandardCompare(b.name)
            if order != .orderedSame { return order == .orderedAscending }
            if a.created != b.created { return (a.created ?? .distantPast) < (b.created ?? .distantPast) }
            return a.id < b.id
        }
    }
    public func download(_ access: String, drive: String, file: String) async throws -> URL {
        let result=try await post("adrive/v1.0/openFile/getDownloadUrl",["drive_id":drive,"file_id":file],token:access)
        guard let text=result["url"] as? String,let url=URL(string:text),url.scheme=="https",url.host != nil,url.user==nil,url.password==nil,url.fragment==nil else { throw CloudError.invalidResponse }
        return url
    }
    private static func file(_ row: [String:Any]) throws -> AliyunFile {
        guard let id=row["file_id"] as? String,!id.isEmpty,!id.contains("/"),!id.contains("\\"),id != ".",id != "..",let name=row["name"] as? String,let type=row["type"] as? String else { throw CloudError.invalidResponse }
        let created=(row["created_at"] as? String).flatMap{ISO8601DateFormatter().date(from:$0)}
        return AliyunFile(id:id,name:name,directory:type=="folder",size:(row["size"] as? NSNumber)?.int64Value ?? 0,created:created)
    }
}
