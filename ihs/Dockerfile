# 仁心醫院 HIS — IBM HTTP Server 9.0.5.25（Archive 版）+ WebSphere Plugin
# docker build 的 context 是專案根目錄（需要 9.0.5-WS-IHS-ARCHIVE-linux-x86_64-FP025.zip）
FROM registry.access.redhat.com/ubi9/ubi:9.4 AS unpack
RUN dnf -q -y install unzip && dnf clean all
COPY 9.0.5-WS-IHS-ARCHIVE-linux-x86_64-FP025.zip /tmp/ihs.zip
RUN cd /opt && unzip -q /tmp/ihs.zip && cd /opt/IHS && ./postinstall.sh

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4
RUN microdnf -y install procps-ng shadow-utils && microdnf clean all
COPY --from=unpack /opt/IHS /opt/IHS
COPY ihs/hospital.conf /opt/IHS/conf/hospital.conf
COPY ihs/plugin-cfg.xml /opt/IHS/plugin/config/webserver1/plugin-cfg.xml
COPY ihs/htdocs/ /opt/IHS/htdocs/
COPY ihs/start.sh /opt/IHS/start.sh
# 非 root 執行：監聽 8080；log 導到 stdout/stderr；載入醫院設定
RUN sed -i -e 's/^Listen 80$/Listen 8080/' \
           -e 's#^ErrorLog logs/error_log#ErrorLog /proc/self/fd/2#' \
           -e 's#^CustomLog logs/access_log common#CustomLog /proc/self/fd/1 hospital#' \
           -e 's/^User nobody/#User nobody/' -e 's/^Group nobody/#Group nobody/' \
           /opt/IHS/conf/httpd.conf && \
    echo 'Include conf/hospital.conf' >> /opt/IHS/conf/httpd.conf && \
    chmod +x /opt/IHS/start.sh && \
    chgrp -R 0 /opt/IHS && chmod -R g=u /opt/IHS
USER 1001
EXPOSE 8080
CMD ["/opt/IHS/start.sh"]
