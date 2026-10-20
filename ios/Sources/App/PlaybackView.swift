import SwiftUI
import AVKit
import UIKit

final class OrientationDelegate:NSObject,UIApplicationDelegate {
    static var mask:UIInterfaceOrientationMask = .allButUpsideDown
    func application(_ application:UIApplication,supportedInterfaceOrientationsFor window:UIWindow?)->UIInterfaceOrientationMask{Self.mask}
}
@MainActor final class PlaybackSession:ObservableObject {
    let player:AVPlayer
    private let target:PlaybackTarget
    private weak var store:MediaStore?
    private var loader:RemoteResourceLoader?
    private var observer:Any?
    private var statusObservation:NSKeyValueObservation?
    private var stopped=false
    @Published var error:String?
    init(target:PlaybackTarget,store:MediaStore){
        self.target=target;self.store=store
        let asset:AVURLAsset
        if let source=target.cloudSource,let file=target.cloudFileID {
            let remote=RemoteResourceLoader(url:target.url){try await CloudAccounts.shared.download(source,file:file)};loader=remote;asset=remote.asset()
        } else if let source=target.source {let remote=RemoteResourceLoader(source:source,password:target.password,url:target.url);loader=remote;asset=remote.asset()}
        else{asset=AVURLAsset(url:target.url)}
        let item=AVPlayerItem(asset:asset);player=AVPlayer(playerItem:item)
        try? AVAudioSession.sharedInstance().setCategory(.playback,mode:.moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        statusObservation=item.observe(\.status,options:[.new,.initial]){[weak self] item,_ in
            Task{@MainActor in
                guard let self,!self.stopped else{return}
                if item.status == .readyToPlay {
                    let position=self.target.history.position
                    if position>0 && position<self.target.history.duration-5 {self.player.seek(to:CMTime(seconds:position,preferredTimescale:600),toleranceBefore:.zero,toleranceAfter:.zero)}
                    self.player.play()
                }else if item.status == .failed {self.error=item.error?.localizedDescription ?? "无法播放此文件或编码。"}
            }
        }
        observer=player.addPeriodicTimeObserver(forInterval:CMTime(seconds:2,preferredTimescale:600),queue:.main){[weak self] time in
            Task { @MainActor in
            guard let self,!self.stopped else{return};let duration=self.player.currentItem?.duration.seconds ?? 0
            self.store?.record(self.target,position:time.seconds,duration:duration)
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-ui-play-first"),time.seconds>0.1,self.player.currentItem?.status == .readyToPlay {
                let size=self.player.currentItem?.presentationSize ?? .zero
                if size.width>0 {let value:[String:Any]=["ready":true,"position":time.seconds,"width":size.width,"height":size.height];if let data=try? JSONSerialization.data(withJSONObject:value){try? data.write(to:MediaStore.mediaDirectory.deletingLastPathComponent().appendingPathComponent("alpha-smoke.json"),options:.atomic)}}
            }
            #endif
            }
        }
    }
    func stop(){guard !stopped else{return};stopped=true;player.pause();if let token=observer{player.removeTimeObserver(token);observer=nil};statusObservation?.invalidate();statusObservation=nil;loader?.close();loader=nil;store?.record(target,position:player.currentTime().seconds,duration:player.currentItem?.duration.seconds ?? 0);player.currentItem?.asset.cancelLoading();player.replaceCurrentItem(with:nil);try? AVAudioSession.sharedInstance().setActive(false,options:.notifyOthersOnDeactivation)}
    deinit{player.pause();if let token=observer{player.removeTimeObserver(token)};statusObservation?.invalidate();loader?.close()}
}
private struct NativeVideo:UIViewControllerRepresentable {
    let player:AVPlayer
    func makeUIViewController(context:Context)->AVPlayerViewController {
        let controller=AVPlayerViewController();controller.player=player;controller.showsPlaybackControls=true;controller.allowsPictureInPicturePlayback=true
        return controller
    }
    func updateUIViewController(_ controller:AVPlayerViewController,context:Context){controller.player=player}
}
struct PlaybackView:View {
    @StateObject private var session:PlaybackSession
    @Environment(\.dismiss) private var dismiss
    init(target:PlaybackTarget,store:MediaStore){_session=StateObject(wrappedValue:PlaybackSession(target:target,store:store))}
    var body:some View {
        ZStack(alignment:.topLeading){
            Color.black.ignoresSafeArea();NativeVideo(player:session.player).ignoresSafeArea()
            Button{dismiss()}label:{Label("完成",systemImage:"chevron.down").font(.body.weight(.semibold)).padding(.horizontal,16).padding(.vertical,10).background(.ultraThinMaterial,in:Capsule())}.padding()
            if let error=session.error {VStack(spacing:12){Image(systemName:"exclamationmark.triangle").font(.largeTitle);Text(error).multilineTextAlignment(.center);Button("返回"){dismiss()}}.padding(24).background(.regularMaterial,in:RoundedRectangle(cornerRadius:24)).frame(maxWidth:360).frame(maxWidth:.infinity,maxHeight:.infinity)}
        }.onAppear{
            OrientationDelegate.mask = .landscape
            UIApplication.shared.connectedScenes.compactMap{$0 as? UIWindowScene}.first?.requestGeometryUpdate(.iOS(interfaceOrientations:.landscape))
        }.onDisappear{
            session.stop();OrientationDelegate.mask = .allButUpsideDown
            UIApplication.shared.connectedScenes.compactMap{$0 as? UIWindowScene}.first?.requestGeometryUpdate(.iOS(interfaceOrientations:.portrait))
        }
    }
}
