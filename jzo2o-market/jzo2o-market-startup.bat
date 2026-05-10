@echo off
chcp 65001
echo.
echo [信息] 打包优惠券工程。
echo.
call  mvn  package -DskipTests=true
echo.
echo [信息] 启动优惠券工程。
echo.
java -Dfile.encoding=utf-8 -Xmx256m -jar target/jzo2o-market.jar
