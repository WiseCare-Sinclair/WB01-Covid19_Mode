## Flow
##### MainActivity.java
- call __connect()__ from __connectFlirOne()__
- call __doConnect()__ after checking identity, Permission, etc in __connect()__
- call __cameraHandler.startStream(streamDataListener)__ from __doConnect()__
##### CameraHandler.java
- call __camera.subscribeStream(thermalImageStreamListener)__ from __startStream()__
- when received the image, call __onImageReceived()__ -> call __accept()__ defined in __handleIncomingImage__
- extract __msxBitmap, dcBitmap__ from __thermalImage__, and call __images(msxBitmap, dcBitmap, thermalImage)__ that is overrided in __MainActivity.java__
##### images() in MainActivity.java (overrided)
- face detect from __dcBitmap__ received as a parameter -> return: __List of face__
- generate __FaceDataHolder(RectF faceBB, String masklabel, double temp, float x, float y) for each face__ and offer it to __facedataQueue__
- put __dcBitmap, facedataQueue__ into Buffers for each frame
- show __Bitmap__ + __[faceBB, Point xy, temp]s (which are in facedataQueue)__ after polling them from Buffers

## Problems
- Frame Drop when multi-face detecting
- The problem of distinguishing between the face to be targeted and the faces passing in the background
- Low Accuracy of the temperature value
- The problem of rotations and symmetry of the images
- latency(not that very serious at least now)

## TODO List
1. 마스크 정보와 온도 정보를 모두 갖는 데이터구조: __FaceDataHolder(RectF faceBB, double temp, float x, float y)__
2. 가장 가까운 Face 구분 문제: 고정 좌표 내의 Face 탐색 또는 가장 큰(넓은) __faceBB__ 를 가진 __FaceDataHolder__ 선택
3. detection을 마친 영상(__dcBitmap__)+얼굴 데이터(__FaceDataHolder.faceBB, FaceDataHolder.masklabel, FaceDataHolder.temp__) 송출 문제
4. Background를 통한 fps 조정(Background 사용: fps 향상, latency 증가 / 사용x: fps 저하, latency 감소)

## Data Structure
- 카메라는 __thermalImage__ 한 프레임을 받아들임
- 각 프레임 당 __dcBitmap__ 과 __facedataQueue__ 를 생성하여 각각 __framesBuffer__ 와 __facesBuffer__ 에 저장
- __facedataQueue__ 에는 해당하는 프레임에 포함된 모든 얼굴들에 일대일대응하는 __FaceDataHolder__ 가 들어있음
- 즉, __dcBitmap__ 한 장에는 __facedataQueue__ 한 개가 대응되고, 이 Queue 안에는 __dcBitmap__ 안에서 인식된 Face들의 정보가 담긴 __FaceDataHolder__ 가 Face 개수만큼 들어있음
