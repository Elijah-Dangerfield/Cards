import SwiftUI
import UIKit
import AuthenticationServices
import ComposeApp
import AVFoundation
import CoreMotion

final class IOSNativeViewFactory: VirtuNativeViewFactory {

    static let shared = IOSNativeViewFactory()
    private let decoder = JSONDecoder()
    private let logger = KLog.shared.withTag(tag:"NativeViewFactory")

    func createAppleSignInButton(
        kind: VirtuNativeAppleSignInButtonKind,
        style: VirtuNativeAppleSignInButtonStyle,
        cornerRadius: Float,
        onTap: @escaping () -> Void
    ) throws -> UIView {
        AppleSignInButtonHost(
            type: kind.buttonType,
            style: style.buttonStyle,
            cornerRadius: CGFloat(cornerRadius),
            action: onTap
        )
    }

    func updateAppleSignInButton(
        view: UIView,
        enabled: Bool,
        onTap: @escaping () -> Void
    ) {
        guard let host = view as? AppleSignInButtonHost else {
            logger.e { "Attempted to update unexpected Apple button view type: \(String(describing: type(of: view)))" }
            return
        }

        host.updateAction(onTap)
        host.setEnabled(enabled)
    }

    func createCameraPreview() throws -> UIView {
        CameraPreviewHost()
    }

    func startCameraPreview(view: UIView) {
        guard let host = view as? CameraPreviewHost else {
            logger.e { "Attempted to start camera on unexpected view type: \(String(describing: type(of: view)))" }
            return
        }
        host.startSession()
    }

    func stopCameraPreview(view: UIView) {
        guard let host = view as? CameraPreviewHost else {
            logger.e { "Attempted to stop camera on unexpected view type: \(String(describing: type(of: view)))" }
            return
        }
        host.stopSession()
    }

    func capturePhoto(view: UIView, onCaptured: @escaping (KotlinByteArray?) -> Void) {
        guard let host = view as? CameraPreviewHost else {
            logger.e { "Attempted to capture photo on unexpected view type: \(String(describing: type(of: view)))" }
            onCaptured(nil)
            return
        }
        host.capturePhoto { data in
            if let data = data {
                let kotlinData = KotlinByteArray(size: Int32(data.count))
                data.withUnsafeBytes { buffer in
                    for (index, byte) in buffer.enumerated() {
                        kotlinData.set(index: Int32(index), value: Int8(bitPattern: byte))
                    }
                }
                onCaptured(kotlinData)
            } else {
                onCaptured(nil)
            }
        }
    }
    
    func toggleCameraFlash(view: UIView) -> Bool {
        guard let host = view as? CameraPreviewHost else {
            logger.e { "Attempted to toggle flash on unexpected view type: \(String(describing: type(of: view)))" }
            return false
        }
        host.toggleFlashPublic()
        return host.isFlashEnabled
    }
    
    func isCameraFlashEnabled(view: UIView) -> Bool {
        guard let host = view as? CameraPreviewHost else {
            return false
        }
        return host.isFlashEnabled
    }
}

@objcMembers
final class AppleSignInButtonHost: UIView {
    private var action: (() -> Void)
    private let button: ASAuthorizationAppleIDButton

    init(
        type: ASAuthorizationAppleIDButton.ButtonType,
        style: ASAuthorizationAppleIDButton.Style,
        cornerRadius: CGFloat,
        action: @escaping () -> Void
    ) {
        self.action = action
        self.button = ASAuthorizationAppleIDButton(type: type, style: style)
        super.init(frame: .zero)

        button.translatesAutoresizingMaskIntoConstraints = false
        button.cornerRadius = cornerRadius
        button.addTarget(self, action: #selector(handleTap), for: .touchUpInside)

        addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: leadingAnchor),
            button.trailingAnchor.constraint(equalTo: trailingAnchor),
            button.topAnchor.constraint(equalTo: topAnchor),
            button.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    @objc private func handleTap() {
        action()
    }

    func updateAction(_ newAction: @escaping () -> Void) {
        action = newAction
    }

    func setEnabled(_ enabled: Bool) {
        button.isEnabled = enabled
        alpha = enabled ? 1.0 : 0.6
    }
}

private extension VirtuNativeAppleSignInButtonKind {
    var buttonType: ASAuthorizationAppleIDButton.ButtonType {
        switch self {
        case .signIn:
            return .signIn
        case .continueFlow:
            return .continue
        }
    }
}

private extension VirtuNativeAppleSignInButtonStyle {
    var buttonStyle: ASAuthorizationAppleIDButton.Style {
        switch self {
        case .black:
            return .black
        case .white:
            return .white
        case .whiteOutline:
            return .whiteOutline
        }
    }
}

// MARK: - Camera Preview

/// Camera guidance state communicated to Kotlin
@objc enum CameraGuidanceState: Int {
    case ready = 0           // Good to capture
    case tiltedTooMuch = 1   // Phone is tilted, suggest holding flat
    case tooDark = 2         // Low light, suggest using flash
    case tooBlurry = 3       // Motion detected, hold steady
}

@objcMembers
final class CameraPreviewHost: UIView, AVCapturePhotoCaptureDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var captureCompletion: ((Data?) -> Void)?
    private let sessionQueue = DispatchQueue(label: "camera.session.queue")
    private let videoQueue = DispatchQueue(label: "camera.video.queue")
    
    // Device reference for flash control
    private var videoDevice: AVCaptureDevice?
    
    // Motion manager for tilt detection (lazy to avoid initialization issues)
    private lazy var motionManager: CMMotionManager = {
        let manager = CMMotionManager()
        manager.deviceMotionUpdateInterval = 0.1
        return manager
    }()
    
    // UI Elements
    private let guideOverlayLayer = CAShapeLayer()
    private let guidanceLabel = UILabel()
    private let flashButton = UIButton(type: .system)
    
    // State
    private(set) var isFlashEnabled = false
    private(set) var currentGuidanceState: CameraGuidanceState = .ready
    private var lastBrightnessValue: Double = 1.0
    var onGuidanceStateChanged: ((CameraGuidanceState) -> Void)?
    
    // Thresholds
    private let tiltThreshold: Double = 0.25  // ~15 degrees from flat
    private let brightnessThreshold: Double = 0.15  // Low light threshold

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupCamera()
        setupUI()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupCamera()
        setupUI()
    }
    
    // MARK: - UI Setup
    
    private func setupUI() {
        setupGuideOverlay()
        setupGuidanceLabel()
        setupFlashButton()
    }
    
    private func setupGuideOverlay() {
        guideOverlayLayer.strokeColor = UIColor.white.withAlphaComponent(0.7).cgColor
        guideOverlayLayer.fillColor = UIColor.clear.cgColor
        guideOverlayLayer.lineWidth = 2
        guideOverlayLayer.lineDashPattern = [10, 5]
        layer.addSublayer(guideOverlayLayer)
    }
    
    private func setupGuidanceLabel() {
        guidanceLabel.textColor = .white
        guidanceLabel.font = .systemFont(ofSize: 14, weight: .medium)
        guidanceLabel.textAlignment = .center
        guidanceLabel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        guidanceLabel.layer.cornerRadius = 8
        guidanceLabel.clipsToBounds = true
        guidanceLabel.isHidden = true
        guidanceLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(guidanceLabel)
        
        NSLayoutConstraint.activate([
            guidanceLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            guidanceLabel.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 16),
            guidanceLabel.heightAnchor.constraint(equalToConstant: 36)
        ])
    }
    
    private func setupFlashButton() {
        let config = UIImage.SymbolConfiguration(pointSize: 24, weight: .medium)
        let flashOffImage = UIImage(systemName: "bolt.slash.fill", withConfiguration: config)
        let flashOnImage = UIImage(systemName: "bolt.fill", withConfiguration: config)
        
        flashButton.setImage(flashOffImage, for: .normal)
        flashButton.setImage(flashOnImage, for: .selected)
        flashButton.tintColor = .white
        flashButton.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        flashButton.layer.cornerRadius = 22
        flashButton.translatesAutoresizingMaskIntoConstraints = false
        flashButton.addTarget(self, action: #selector(toggleFlash), for: .touchUpInside)
        addSubview(flashButton)
        
        NSLayoutConstraint.activate([
            flashButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            flashButton.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 16),
            flashButton.widthAnchor.constraint(equalToConstant: 44),
            flashButton.heightAnchor.constraint(equalToConstant: 44)
        ])
        
        // Hide flash button if device doesn't have flash
        flashButton.isHidden = !(videoDevice?.hasTorch ?? false)
    }
    
    @objc private func toggleFlash() {
        toggleFlashInternal()
    }
    
    /// Public method for programmatic flash toggle (called from NativeViewFactory)
    func toggleFlashPublic() {
        toggleFlashInternal()
    }
    
    private func toggleFlashInternal() {
        isFlashEnabled.toggle()
        flashButton.isSelected = isFlashEnabled
        
        // Update torch mode for live preview
        guard let device = videoDevice, device.hasTorch else { return }
        
        do {
            try device.lockForConfiguration()
            device.torchMode = isFlashEnabled ? .on : .off
            device.unlockForConfiguration()
        } catch {
            print("Failed to toggle torch: \(error)")
        }
    }
    
    private func updateGuideOverlay() {
        // Don't update if bounds aren't ready yet
        guard bounds.width > 0 && bounds.height > 0 else { return }
        
        // Draw a receipt-shaped guide (tall rectangle with rounded corners)
        let padding: CGFloat = 40
        let guideWidth = bounds.width - (padding * 2)
        guard guideWidth > 0 else { return }
        
        let guideHeight = guideWidth * 1.5  // Receipt aspect ratio ~2:3
        
        let guideRect = CGRect(
            x: padding,
            y: (bounds.height - guideHeight) / 2,
            width: guideWidth,
            height: guideHeight
        )
        
        let path = UIBezierPath(roundedRect: guideRect, cornerRadius: 12)
        guideOverlayLayer.path = path.cgPath
    }

    private func setupCamera() {
        backgroundColor = .black

        sessionQueue.async { [weak self] in
            self?.configureCaptureSession()
        }
    }

    private func configureCaptureSession() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .photo

        // Add video input
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let videoInput = try? AVCaptureDeviceInput(device: device),
              captureSession.canAddInput(videoInput) else {
            captureSession.commitConfiguration()
            return
        }
        captureSession.addInput(videoInput)
        videoDevice = device

        // Add photo output
        guard captureSession.canAddOutput(photoOutput) else {
            captureSession.commitConfiguration()
            return
        }
        captureSession.addOutput(photoOutput)
        photoOutput.isHighResolutionCaptureEnabled = true
        
        // Add video output for brightness detection
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        captureSession.commitConfiguration()

        DispatchQueue.main.async { [weak self] in
            self?.setupPreviewLayer()
            self?.flashButton.isHidden = !(device.hasTorch)
        }
    }

    private func setupPreviewLayer() {
        let layer = AVCaptureVideoPreviewLayer(session: captureSession)
        layer.videoGravity = .resizeAspectFill
        layer.frame = bounds
        self.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
        
        // Update guide overlay after preview layer is set
        updateGuideOverlay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
        updateGuideOverlay()
    }

    func startSession() {
        sessionQueue.async { [weak self] in
            guard let self = self, !self.captureSession.isRunning else { return }
            self.captureSession.startRunning()
        }
        startMotionUpdates()
    }

    func stopSession() {
        sessionQueue.async { [weak self] in
            guard let self = self, self.captureSession.isRunning else { return }
            self.captureSession.stopRunning()
        }
        stopMotionUpdates()
        
        // Turn off torch when stopping
        if let device = videoDevice, device.hasTorch && device.torchMode == .on {
            do {
                try device.lockForConfiguration()
                device.torchMode = .off
                device.unlockForConfiguration()
            } catch {}
        }
    }
    
    // MARK: - Motion Detection (Tilt)
    
    private func startMotionUpdates() {
        // Skip motion updates on simulator or if not available
        #if targetEnvironment(simulator)
        // Motion detection doesn't work well on simulator, skip it
        return
        #else
        guard motionManager.isDeviceMotionAvailable else { return }
        
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, error in
            guard let self = self, let motion = motion, error == nil else { return }
            
            // Check tilt - gravity.z is 1 when flat, 0 when vertical
            // We want to detect when phone is too tilted (not parallel to surface)
            let zGravity = abs(motion.gravity.z)
            let isTilted = zGravity < (1.0 - self.tiltThreshold)
            
            self.updateGuidanceState(isTilted: isTilted)
        }
        #endif
    }
    
    private func stopMotionUpdates() {
        #if !targetEnvironment(simulator)
        if motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
        }
        #endif
    }
    
    private func updateGuidanceState(isTilted: Bool) {
        let newState: CameraGuidanceState
        
        if isTilted {
            newState = .tiltedTooMuch
        } else if lastBrightnessValue < brightnessThreshold && !isFlashEnabled {
            newState = .tooDark
        } else {
            newState = .ready
        }
        
        if newState != currentGuidanceState {
            currentGuidanceState = newState
            updateGuidanceUI()
            onGuidanceStateChanged?(newState)
        }
    }
    
    private func updateGuidanceUI() {
        switch currentGuidanceState {
        case .ready:
            guidanceLabel.isHidden = true
            guideOverlayLayer.strokeColor = UIColor.white.withAlphaComponent(0.7).cgColor
        case .tiltedTooMuch:
            guidanceLabel.text = "   Hold phone flat over receipt   "
            guidanceLabel.isHidden = false
            guideOverlayLayer.strokeColor = UIColor.orange.cgColor
        case .tooDark:
            guidanceLabel.text = "   Too dark - try using flash ⚡   "
            guidanceLabel.isHidden = false
            guideOverlayLayer.strokeColor = UIColor.orange.cgColor
        case .tooBlurry:
            guidanceLabel.text = "   Hold steady   "
            guidanceLabel.isHidden = false
            guideOverlayLayer.strokeColor = UIColor.orange.cgColor
        }
    }
    
    // MARK: - Video Output Delegate (Brightness Detection)
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        // Ensure sample buffer has valid format description to avoid CMVideoFormatDescriptionGetDimensions errors
        guard CMSampleBufferIsValid(sampleBuffer),
              CMSampleBufferGetFormatDescription(sampleBuffer) != nil else {
            return
        }
        
        // Get brightness from sample buffer metadata
        guard let metadata = CMCopyDictionaryOfAttachments(allocator: nil, target: sampleBuffer, attachmentMode: kCMAttachmentMode_ShouldPropagate) as? [String: Any],
              let exifData = metadata["{Exif}"] as? [String: Any],
              let brightness = exifData["BrightnessValue"] as? Double else {
            return
        }
        
        // Normalize brightness (typically -5 to 12, we care about low values)
        let normalizedBrightness = (brightness + 5) / 17  // Rough normalization to 0-1
        lastBrightnessValue = max(0, min(1, normalizedBrightness))
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            // Re-evaluate guidance state with new brightness
            let isTilted = self.currentGuidanceState == .tiltedTooMuch
            self.updateGuidanceState(isTilted: isTilted)
        }
    }

    func capturePhoto(completion: @escaping (Data?) -> Void) {
        captureCompletion = completion
        let settings = AVCapturePhotoSettings()
        settings.isHighResolutionPhotoEnabled = true
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    // MARK: - AVCapturePhotoCaptureDelegate

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        DispatchQueue.main.async { [weak self] in
            if let error = error {
                print("Photo capture error: \(error.localizedDescription)")
                self?.captureCompletion?(nil)
            } else if let data = photo.fileDataRepresentation() {
                // Compress to JPEG for smaller size
                if let image = UIImage(data: data),
                   let jpegData = image.jpegData(compressionQuality: 0.8) {
                    self?.captureCompletion?(jpegData)
                } else {
                    self?.captureCompletion?(data)
                }
            } else {
                self?.captureCompletion?(nil)
            }
            self?.captureCompletion = nil
        }
    }
}