import SwiftUI
import AVFoundation
import Security

struct PlayedVideo: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var localFile: String? = nil
    var sourceID: UUID? = nil
    var remoteURL: URL? = nil
    var cloudSourceID: UUID? = nil
    var cloudFileID: String? = nil
    var position: Double
    var duration: Double
    var updated: Date
}
struct PlaybackTarget: Identifiable {
    let id=UUID()
    let title: String
    let url: URL
    let source: DAVSource?
    let password: String
    let history: PlayedVideo
    var cloudSource: AliyunSource? = nil
    var cloudFileID: String? = nil
    let historyEpoch: Int
}
enum PasswordVault {
    static let service="io.localplayer.app.webdav"
    static func read(_ id: UUID) -> String {
        let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:id.uuidString,kSecReturnData as String:true,kSecMatchLimit as String:kSecMatchLimitOne]
        var result:CFTypeRef?;guard SecItemCopyMatching(query as CFDictionary,&result)==errSecSuccess,let data=result as? Data else{return ""}
        return String(data:data,encoding:.utf8) ?? ""
    }
    static func save(_ value: String,id: UUID) throws {
        let key:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:id.uuidString]
        let attrs:[String:Any]=[kSecValueData as String:Data(value.utf8),kSecAttrAccessible as String:kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let result=SecItemUpdate(key as CFDictionary,attrs as CFDictionary)
        if result==errSecItemNotFound {var add=key;attrs.forEach{add[$0.key]=$0.value};let added=SecItemAdd(add as CFDictionary,nil);guard added==errSecSuccess else{throw NSError(domain:"Keychain",code:Int(added),userInfo:[NSLocalizedDescriptionKey:"无法保存来源凭据"])} }
        else if result != errSecSuccess {throw NSError(domain:"Keychain",code:Int(result),userInfo:[NSLocalizedDescriptionKey:"无法更新来源凭据"])}
    }
    static func remove(_ id: UUID){SecItemDelete([kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:id.uuidString] as CFDictionary)}
}
@MainActor final class MediaStore: ObservableObject {
    @Published var sources=[DAVSource]()
    @Published var cloudSources=[AliyunSource]()
    let cloudAuthorization=CloudAuthorization()
    @Published var history=[PlayedVideo]()
    @Published var localFiles=[URL]()
    @Published var playing: PlaybackTarget?
    @Published var message: String?
    @Published var importing=false
    private let defaults=UserDefaults.standard
    private var historyEpoch=0
    init(){cloudSources=read("cloud-sources",as:[AliyunSource].self) ?? [];sources=read("sources",as:[DAVSource].self) ?? [];history=read("history",as:[PlayedVideo].self) ?? [];scanFiles()}
    nonisolated static var mediaDirectory: URL {FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("Imported",isDirectory:true)}
    private func read<T:Decodable>(_ key:String,as:T.Type)->T? {guard let data=defaults.data(forKey:key) else{return nil};return try? JSONDecoder().decode(T.self,from:data)}
    private func persist(){defaults.set(try? JSONEncoder().encode(cloudSources),forKey:"cloud-sources");defaults.set(try? JSONEncoder().encode(sources),forKey:"sources");defaults.set(try? JSONEncoder().encode(history),forKey:"history")}
    func scanFiles(){try? FileManager.default.createDirectory(at:Self.mediaDirectory,withIntermediateDirectories:true);localFiles=((try? FileManager.default.contentsOfDirectory(at:Self.mediaDirectory,includingPropertiesForKeys:[.isRegularFileKey],options:[.skipsHiddenFiles])) ?? []).filter{(try? $0.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile)==true}.sorted{$0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending}}
    func importFile(_ url: URL) async {
        importing=true;defer{importing=false}
        do {
            _ = try await Task.detached(priority:.userInitiated) { () throws -> URL in
                let scoped=url.startAccessingSecurityScopedResource();defer{if scoped{url.stopAccessingSecurityScopedResource()}}
                let folder=Self.mediaDirectory;try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
                var target=folder.appendingPathComponent(url.lastPathComponent)
                if FileManager.default.fileExists(atPath:target.path){target=folder.appendingPathComponent(url.deletingPathExtension().lastPathComponent+"-"+String(UUID().uuidString.prefix(6))+"."+url.pathExtension)}
                try FileManager.default.copyItem(at:url,to:target)
                var values=URLResourceValues();values.isExcludedFromBackup=true;try? target.setResourceValues(values)
                return target
            }.value
            scanFiles()
        }catch{message="导入失败："+error.localizedDescription}
    }
    func add(_ source:DAVSource,password:String) throws {try PasswordVault.save(password,id:source.id);sources.append(source);persist()}
    func remove(_ source:DAVSource){if playing?.source?.id==source.id{playing=nil};sources.removeAll{$0.id==source.id};history.removeAll{$0.sourceID==source.id};PasswordVault.remove(source.id);persist()}
    func playLocal(_ url:URL){let id="local:"+url.lastPathComponent;let previous=history.first{$0.id==id};let record=previous ?? PlayedVideo(id:id,title:url.lastPathComponent,localFile:url.lastPathComponent,position:0,duration:0,updated:Date());playing=PlaybackTarget(title:record.title,url:url,source:nil,password:"",history:record,historyEpoch:historyEpoch)}
    func playRemote(_ entry:DAVEntry,source:DAVSource){let id=source.id.uuidString+":"+entry.url.absoluteString;let record=history.first{$0.id==id} ?? PlayedVideo(id:id,title:entry.name,sourceID:source.id,remoteURL:entry.url,position:0,duration:0,updated:Date());playing=PlaybackTarget(title:entry.name,url:entry.url,source:source,password:PasswordVault.read(source.id),history:record,historyEpoch:historyEpoch)}
    func resume(_ record:PlayedVideo){
        if let id=record.cloudSourceID,let file=record.cloudFileID{guard let source=cloudSources.first(where:{$0.id==id})else{message="原网盘来源已不存在";return};Task{await playCloud(file,title:record.title,source:source)};return}
        if let file=record.localFile{let url=Self.mediaDirectory.appendingPathComponent(file);guard FileManager.default.fileExists(atPath:url.path) else{message="原文件已不存在";return};playLocal(url)}else if let id=record.sourceID,let source=sources.first(where:{$0.id==id}),let url=record.remoteURL{playRemote(DAVEntry(url:url,name:record.title,directory:false,size:0),source:source)}else{message="原来源已不存在"}}
    func record(_ target:PlaybackTarget,position:Double,duration:Double){guard target.historyEpoch==historyEpoch, (target.source==nil || sources.contains(where:{$0.id==target.source?.id})) && (target.cloudSource==nil || cloudSources.contains(where:{$0.id==target.cloudSource?.id})),position.isFinite,duration.isFinite,duration>0 else{return};var row=target.history;row.position=max(0,min(position,duration));row.duration=duration;row.updated=Date();history.removeAll{$0.id==row.id};history.insert(row,at:0);history=Array(history.prefix(100));persist()}
    func connectCloud() async {
        do{let source=try await cloudAuthorization.authorize(known:cloudSources);try Task.checkCancellation();cloudSources.removeAll{$0.id==source.id};cloudSources.append(source);persist()}
        catch is CancellationError{}catch{message=error.localizedDescription}
    }
    func removeCloud(_ source:AliyunSource) {
        cloudAuthorization.cancel()
        if playing?.cloudSource?.id==source.id{playing=nil}
        cloudSources.removeAll{$0.id==source.id};history.removeAll{$0.cloudSourceID==source.id};persist()
        Task{await CloudAccounts.shared.remove(source.id)}
    }
    func playCloud(_ file:String,title:String,source:AliyunSource) async {
        let epoch=historyEpoch
        do {
            let url=try await CloudAccounts.shared.download(source,file:file);try Task.checkCancellation()
            guard cloudSources.contains(where:{$0.id==source.id}),epoch==historyEpoch else{return}
            let id="aliyun:"+source.id.uuidString+":"+file
            let record=history.first{$0.id==id} ?? PlayedVideo(id:id,title:title,cloudSourceID:source.id,cloudFileID:file,position:0,duration:0,updated:Date())
            playing=PlaybackTarget(title:title,url:url,source:nil,password:"",history:record,cloudSource:source,cloudFileID:file,historyEpoch:epoch)
        }catch is CancellationError{}catch{message=error.localizedDescription}
    }
    func clearHistory(){historyEpoch+=1;history=[];persist()}
}
