import Foundation
import AVFoundation
import CoreVideo
let output=URL(fileURLWithPath:CommandLine.arguments[1])
let writer=try AVAssetWriter(outputURL:output,fileType:.mp4)
let input=AVAssetWriterInput(mediaType:.video,outputSettings:[AVVideoCodecKey:AVVideoCodecType.h264,AVVideoWidthKey:320,AVVideoHeightKey:180])
let adaptor=AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String:320,kCVPixelBufferHeightKey as String:180,kCVPixelBufferIOSurfacePropertiesKey as String:[:]])
writer.add(input);guard writer.startWriting() else{throw writer.error!};writer.startSession(atSourceTime:.zero)
for frame in 0..<90 {
    while !input.isReadyForMoreMediaData{Thread.sleep(forTimeInterval:0.01)}
    var buffer:CVPixelBuffer?;CVPixelBufferCreate(kCFAllocatorDefault,320,180,kCVPixelFormatType_32ARGB,nil,&buffer)
    guard let buffer else{fatalError("pixel buffer failed")};CVPixelBufferLockBaseAddress(buffer,[])
    let address=CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to:UInt32.self),stride=CVPixelBufferGetBytesPerRow(buffer)/4
    for y in 0..<180{for x in 0..<320{address[y*stride+x]=x<160 ? 0xFF2040D0 : 0xFFB05030}}
    CVPixelBufferUnlockBaseAddress(buffer,[])
    guard adaptor.append(buffer,withPresentationTime:CMTime(value:Int64(frame),timescale:30)) else{throw writer.error ?? NSError(domain:"fixture",code:1)}
}
input.markAsFinished();let done=DispatchSemaphore(value:0);writer.finishWriting{done.signal()};guard done.wait(timeout:.now()+30) == .success,writer.status == .completed else{throw writer.error ?? NSError(domain:"fixture",code:2)}
