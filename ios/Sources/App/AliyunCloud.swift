import SwiftUI
import Security

actor CloudAccounts {
    static let shared=CloudAccounts()
    private let service="io.localplayer.app.aliyun.oauth"
    private let api=AliyunAPI()
    private var revisions=[UUID:Int]()
    private var refreshing=[UUID:Task<AliyunToken,Error>]()
    private var removed=Set<UUID>()
    private func key(_ id:UUID)->[String:Any] { [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:id.uuidString] }
    private func read(_ id:UUID) throws -> AliyunToken {
        var query=key(id);query[kSecReturnData as String]=true;query[kSecMatchLimit as String]=kSecMatchLimitOne
        var result:CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary,&result)==errSecSuccess,let data=result as? Data,let token=try? JSONDecoder().decode(AliyunToken.self,from:data) else { throw CloudError.unauthorized }
        return token
    }
    func save(_ token:AliyunToken,id:UUID) throws {
        guard !removed.contains(id) else{throw CloudError.unauthorized}
        let data=try JSONEncoder().encode(token)
        let attrs:[String:Any]=[kSecValueData as String:data,kSecAttrAccessible as String:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let query=key(id);let result=SecItemUpdate(query as CFDictionary,attrs as CFDictionary)
        if result==errSecItemNotFound { var add=query;attrs.forEach{add[$0.key]=$0.value};guard SecItemAdd(add as CFDictionary,nil)==errSecSuccess else {throw CloudError.unauthorized} }
        else if result != errSecSuccess {throw CloudError.unauthorized}
        revisions[id,default:0]+=1
    }
    func remove(_ id:UUID) {
        removed.insert(id)
        revisions[id,default:0]+=1;refreshing.removeValue(forKey:id)?.cancel();SecItemDelete(key(id) as CFDictionary)
    }
    private func token(_ id:UUID,force:Bool=false) async throws -> AliyunToken {
        let current=try read(id)
        if !force && current.expires.timeIntervalSinceNow>60 {return current}
        if let task=refreshing[id] {let value=try await task.value;guard !removed.contains(id) else{throw CloudError.unauthorized};return value}
        guard let refresh=current.refresh else {throw CloudError.unauthorized}
        let revision=revisions[id,default:0]
        let task=Task {try await api.refresh(refresh)};refreshing[id]=task
        defer{refreshing[id]=nil}
        do {
            let updated=try await task.value
            guard revisions[id,default:0]==revision else {throw CloudError.unauthorized}
            try save(updated,id:id);return updated
        }catch is CancellationError{throw CancellationError()}catch{throw CloudError.unauthorized}
    }
    private func authorized<T>(_ source:AliyunSource, action:(String) async throws -> T) async throws -> T {
        let current=try await token(source.id)
        do{return try await action(current.access)}catch CloudError.unauthorized{return try await action(token(source.id,force:true).access)}
    }
    func list(_ source:AliyunSource,parent:String) async throws -> [AliyunFile] {try await authorized(source){try await api.list($0,drive:source.driveID,parent:parent)}}
    func download(_ source:AliyunSource,file:String) async throws -> URL {try await authorized(source){try await api.download($0,drive:source.driveID,file:file)}}
}

@MainActor final class CloudAuthorization:ObservableObject {
    @Published private(set) var busy=false
    private struct Pending {
        let id:UUID,grant:AliyunPKCE,known:[AliyunSource]
        let continuation:CheckedContinuation<AliyunSource,Error>
        var claimed=false
    }
    private var pending:Pending?
    private var timer:Task<Void,Never>?
    private var work:Task<Void,Never>?
    private let api=AliyunAPI()
    func authorize(known:[AliyunSource]) async throws -> AliyunSource {
        guard UIApplication.shared.canOpenURL(URL(string:"smartdrive://")!) else {throw CloudError.notInstalled}
        cancel()
        let grant=try AliyunPKCE(),id=UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let flow=Pending(id:id,grant:grant,known:known,continuation:continuation);pending=flow;busy=true
                timer=Task{try? await Task.sleep(for:.seconds(600));if !Task.isCancelled{finish(.failure(CloudError.cancelled),id:flow.id)}}
                work=Task {
                    do {
                        let url=try await api.authorization(grant);try Task.checkCancellation()
                        guard pending?.id==flow.id else{return}
                        let opened=await UIApplication.shared.open(url,options:url.scheme=="https" ? [.universalLinksOnly:true] : [:])
                        if !opened{finish(.failure(CloudError.notInstalled),id:flow.id)}
                    }catch{finish(.failure(error),id:flow.id)}
                }
            }
        } onCancel:{Task{@MainActor in self.finish(.failure(CloudError.cancelled),id:id)}}
    }
    func cancel(){if let flow=pending{finish(.failure(CloudError.cancelled),id:flow.id)}}
    private func finish(_ result:Result<AliyunSource,Error>,id:UUID){
        guard let flow=pending,flow.id==id else{return};pending=nil;busy=false;timer?.cancel();timer=nil;work?.cancel();work=nil
        flow.continuation.resume(with:result)
    }
    func callback(_ url:URL) -> Bool {
        guard var flow=pending,!flow.claimed,url.scheme==AliyunConfiguration.callbackScheme,
              let items=URLComponents(url:url,resolvingAgainstBaseURL:false)?.queryItems else{return false}
        func value(_ name:String)->String?{let matches=items.filter{$0.name==name};return matches.count==1 ? matches[0].value : nil}
        guard value("state")==flow.grant.state else{return false}
        if value("error") != nil{finish(.failure(CloudError.cancelled),id:flow.id);return true}
        guard let code=value("code"),!code.isEmpty,code.count<=4096 else{return false}
        flow.claimed=true;pending=flow
        work=Task {
            do {
                let token=try await api.exchange(code:code,verifier:flow.grant.verifier)
                let drive=try await api.drive(token.access);try Task.checkCancellation()
                guard pending?.id==flow.id else{return}
                let existing=flow.known.first{$0.userID==drive.user && $0.driveID==drive.drive}
                let source=AliyunSource(id:existing?.id ?? UUID(),userID:drive.user,driveID:drive.drive,name:"阿里云盘 · "+drive.name)
                try await CloudAccounts.shared.save(token,id:source.id);try Task.checkCancellation()
                guard pending?.id==flow.id else{return}
                finish(.success(source),id:flow.id)
            }catch{finish(.failure(error),id:flow.id)}
        }
        return true
    }
}

struct CloudBrowser:View {
    @EnvironmentObject private var store:MediaStore
    let source:AliyunSource
    var parent="root"
    var title:String?=nil
    @State private var files=[AliyunFile]()
    @State private var busy=true
    @State private var error:String?
    @State private var revision=0
    var body:some View {
        List {
            if busy{ProgressView("正在读取阿里云盘…")}
            if let error{Section{Text(error).foregroundStyle(.secondary);Button("重试"){revision+=1};Button("重新授权"){Task{await store.connectCloud()}}}}
            ForEach(files){file in
                if file.directory{NavigationLink{CloudBrowser(source:source,parent:file.id,title:file.name)}label:{Label(file.name,systemImage:"folder")}}
                else if file.isVideo{Button{Task{await store.playCloud(file.id,title:file.name,source:source)}}label:{Label(file.name,systemImage:"play.rectangle").lineLimit(2)}}
            }
            if !busy && error==nil && files.isEmpty{ContentUnavailableView("目录为空",systemImage:"folder")}
        }.navigationTitle(title ?? source.name).navigationBarTitleDisplayMode(.inline)
        .task(id:revision){busy=true;error=nil;do{let result=try await CloudAccounts.shared.list(source,parent:parent);try Task.checkCancellation();files=result}catch is CancellationError{}catch{if !Task.isCancelled{self.error=error.localizedDescription}};busy=false}
    }
}

struct CloudConnectButton:View {
    @EnvironmentObject private var store:MediaStore
    @State private var connecting=false
    var body:some View {
        VStack(alignment:.leading,spacing:10){
            Button{connecting=true;Task{await store.connectCloud();connecting=false}}label:{Label(connecting ? "等待阿里云盘授权…" : "登录阿里云盘",systemImage:"cloud")}.disabled(connecting)
            if connecting{Button("取消授权",role:.cancel){store.cloudAuthorization.cancel()}}
            Text("仅申请读取权限。需安装阿里云盘官方 App；百度网盘即将支持。").font(.caption).foregroundStyle(.secondary)
        }
    }
}
