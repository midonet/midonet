# configure options
%define 	with_snmp	1
%define		with_vtysh	1
%define		with_ospf_te	1
%define		with_nssa	1
%define		with_opaque_lsa 1
%define		with_tcp_zebra	0
%define		with_pam	0
%define		with_ipv6	1
%define		with_ospfclient 1
%define		with_ospfapi	1
%define         with_rtadv      1
%define		with_multipath	64
%define		quagga_uid      92
%define		quagga_gid      92
%define		quagga_user	quagga
%define		vty_group	quaggavt
%define		vty_gid		85

# path defines
%define		_sysconfdir	/etc/quagga
%define		zeb_src		%{_builddir}/%{name}-%{version}
%define		zeb_rh_src	%{zeb_src}/redhat
%define		zeb_docs	%{zeb_src}/doc

# defines for configure
%define		_libexecdir	%{_exec_prefix}/libexec/quagga
%define		_includedir	%{_prefix}/include
%define		_libdir		%{_exec_prefix}/%{_lib}/quagga
%define		_localstatedir	/var/run/quagga

Summary:    Routing daemon
Name:		quagga
Version:	0.99.21
Release:    1%{?dist}.midokura.1
License:	GPLv2+
Group:      System Environment/Daemons
Source0:	http://www.quagga.net/download/%{name}-%{version}.tar.gz
Patch22:	quagga-0.99.15-CVE-2012-1820.patch

URL:		http://www.quagga.net
%if %with_snmp
BuildRequires:	net-snmp-devel
Requires:	net-snmp
%endif
%if %with_vtysh
BuildRequires:	readline readline-devel ncurses ncurses-devel
Requires:	ncurses
%endif
BuildRequires:	texinfo tetex autoconf patch libcap-devel texi2html
%if %with_pam
BuildRequires:  pam-devel
%endif
%define __perl_requires %{SOURCE1}

# Initscripts > 5.60 is required for IPv6 support
Requires:	initscripts >= 5.60
Requires:	ncurses
%if %with_pam
Requires:	pam
%endif
Requires(post): /sbin/install-info
Requires(preun): /sbin/install-info
Provides:	routingdaemon = %{version}-%{release}
BuildRoot:	%{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)
Conflicts:	bird gated mrt zebra

%description
Quagga is a free software that manages TCP/IP based routing
protocol. It takes multi-server and multi-thread approach to resolve
the current complexity of the Internet.

Quagga supports BGP4, BGP4+, OSPFv2, OSPFv3, RIPv1, RIPv2, and RIPng.

Quagga is intended to be used as a Route Server and a Route Reflector. It is
not a toolkit, it provides full routing power under a new architecture.
Quagga by design has a process for each protocol.

Quagga is a fork of GNU Zebra.

%package contrib
Summary: Contrib tools for quagga
Group: System Environment/Daemons

%description contrib
Contributed/3rd party tools which may be of use with quagga.

%package devel
Summary: Header and object files for quagga development
Group: System Environment/Daemons

%description devel
The quagga-devel package contains the header and object files necessary for
developing OSPF-API and quagga applications.

%prep
%setup  -q
%patch22 -p1 -b .CVE-2012-1820

%build
# FC5+ automatic -fstack-protector-all switch
export RPM_OPT_FLAGS=${RPM_OPT_FLAGS//-fstack-protector/-fstack-protector-all}
#./autogen.sh                                                                 
export CFLAGS="$RPM_OPT_FLAGS $CPPFLAGS -fno-strict-aliasing"
export CXXFLAGS="$RPM_OPT_FLAGS $CPPFLAGS"

%configure \
%if %with_ipv6
	--enable-ipv6=yes \
%endif
%if %with_snmp
	--enable-snmp=yes \
%endif
%if %with_multipath
	--enable-multipath=%with_multipath \
%endif
%if %with_tcp_zebra
	--enable-tcp-zebra \
%endif
%if %with_nssa
	--enable-nssa \
%endif
%if %with_opaque_lsa
	--enable-opaque-lsa \
%endif
%if %with_ospf_te
	--enable-ospf-te \
%endif
%if %with_vtysh
	--enable-vtysh=yes \
%endif
%if %with_ospfclient 
	--enable-ospfclient=yes \
%else
	--enable-ospfclient=no\
%endif
%if %with_ospfapi
	--enable-ospfapi=yes \
%else
	--enable-ospfapi=no \
%endif
%if %with_pam
	--with-libpam \
%endif
%if %quagga_user
	--enable-user=%quagga_user \
	--enable-group=%quagga_user \
%endif
%if %vty_group
	--enable-vty-group=%vty_group \
%endif
%if %with_rtadv
	--enable-rtadv \
%endif
--enable-netlink

for X in Makefile */Makefile ; do perl -pe 's/^COMPILE \= \$\(CC\) /COMPILE = \$(CC) -fPIE /;s/^LDFLAGS = $/LDFLAGS = -pie/' < $X > $X.tmp ; mv $X.tmp $X ; done

make %{?_smp_mflags} MAKEINFO="makeinfo --no-split"

pushd doc
texi2html quagga.texi
popd

%install
rm -rf $RPM_BUILD_ROOT

install -d $RPM_BUILD_ROOT/etc/{rc.d/init.d,sysconfig,logrotate.d} \
	$RPM_BUILD_ROOT/var/log/quagga $RPM_BUILD_ROOT%{_infodir}

%if %with_pam
install -d $RPM_BUILD_ROOT/etc/pam.d
%endif

make install \
	DESTDIR=$RPM_BUILD_ROOT

# Remove this file, as it is uninstalled and causes errors when building on RH9
rm -rf $RPM_BUILD_ROOT/usr/share/info/dir

install %{zeb_rh_src}/zebra.init $RPM_BUILD_ROOT/etc/rc.d/init.d/zebra
install %{zeb_rh_src}/bgpd.init $RPM_BUILD_ROOT/etc/rc.d/init.d/bgpd
%if %with_ipv6
install %{zeb_rh_src}/ospf6d.init $RPM_BUILD_ROOT/etc/rc.d/init.d/ospf6d
install %{zeb_rh_src}/ripngd.init $RPM_BUILD_ROOT/etc/rc.d/init.d/ripngd
%endif
install %{zeb_rh_src}/ospfd.init $RPM_BUILD_ROOT/etc/rc.d/init.d/ospfd
install %{zeb_rh_src}/ripd.init $RPM_BUILD_ROOT/etc/rc.d/init.d/ripd
install -m644 %{zeb_rh_src}/quagga.sysconfig $RPM_BUILD_ROOT/etc/sysconfig/quagga
%if %with_pam
install -m644 %{zeb_rh_src}/quagga.pam $RPM_BUILD_ROOT/etc/pam.d/quagga
%endif
install -m644 %{zeb_rh_src}/quagga.logrotate $RPM_BUILD_ROOT/etc/logrotate.d/quagga
install -d -m770  $RPM_BUILD_ROOT/var/run/quagga

%pre
# add vty_group
%if %vty_group
groupadd -g %vty_gid -r %vty_group 2> /dev/null || :
%endif
# add quagga user and group
%if %quagga_user
# Ensure that quagga_gid gets correctly allocated
if getent group %quagga_user >/dev/null 2>&1 ; then : ; else \
 /usr/sbin/groupadd -g %quagga_gid %quagga_user > /dev/null 2>&1 || exit 1 ; fi
if getent passwd %quagga_user >/dev/null 2>&1 ; then : ; else \
 /usr/sbin/useradd -u %quagga_uid -g %quagga_gid -M -r -s /sbin/nologin \
 -c "Quagga routing suite" -d %_localstatedir %quagga_user 2> /dev/null \
 || exit 1 ; fi
%endif

%post
# /etc/services is already populated, so skip this

# zebra_spec_add_service <service name> <port/proto> <comment>
# e.g. zebra_spec_add_service zebrasrv 2600/tcp "zebra service"
#
#zebra_spec_add_service ()
#{
#  # Add port /etc/services entry if it isn't already there 
#  if [ -f /etc/services ] && ! grep -q "^$1[^a-zA-Z0-9]" /etc/services ; then
#    echo "$1		$2			# $3"  >> /etc/services
#  fi
#}
#
#zebra_spec_add_service zebrasrv 2600/tcp "zebra service"
#zebra_spec_add_service zebra    2601/tcp "zebra vty"
#zebra_spec_add_service ripd     2602/tcp "RIPd vty"
#%if %with_ipv6
#zebra_spec_add_service ripngd   2603/tcp "RIPngd vty"
#%endif
#zebra_spec_add_service ospfd    2604/tcp "OSPFd vty"
#zebra_spec_add_service bgpd     2605/tcp "BGPd vty"
#%if %with_ipv6
#zebra_spec_add_service ospf6d   2606/tcp "OSPF6d vty"
#%endif
#%if %with_ospfapi
#zebra_spec_add_service ospfapi  2607/tcp "OSPF-API"
#%endif

/sbin/chkconfig --add zebra 
/sbin/chkconfig --add ripd
%if %with_ipv6
/sbin/chkconfig --add ripngd
/sbin/chkconfig --add ospf6d
%endif
/sbin/chkconfig --add ospfd
/sbin/chkconfig --add bgpd

if [ -f %{_infodir}/%{name}.inf* ]; then
	/sbin/install-info %{_infodir}/%{name}.info %{_infodir}/dir || :
fi

# Create dummy files if they don't exist so basic functions can be used.
if [ ! -e %{_sysconfdir}/zebra.conf ]; then
	echo "hostname `hostname`" > %{_sysconfdir}/zebra.conf
%if %quagga_user
	chown %quagga_user:%quagga_user %{_sysconfdir}/zebra.conf
%endif
	chmod 640 %{_sysconfdir}/zebra.conf
fi
if [ ! -e %{_sysconfdir}/vtysh.conf ]; then
	touch %{_sysconfdir}/vtysh.conf
	chmod 640 %{_sysconfdir}/vtysh.conf
	chown %{quagga_user}:%{vty_group} %{_sysconfdir}/vtysh.conf
fi

%postun
if [ "$1" -ge  "1" ]; then
	/etc/rc.d/init.d/zebra  condrestart >/dev/null 2>&1
	/etc/rc.d/init.d/ripd   condrestart >/dev/null 2>&1
%if %with_ipv6
	/etc/rc.d/init.d/ripngd condrestart >/dev/null 2>&1
%endif
	/etc/rc.d/init.d/ospfd  condrestart >/dev/null 2>&1
%if %with_ipv6
	/etc/rc.d/init.d/ospf6d condrestart >/dev/null 2>&1
%endif
	/etc/rc.d/init.d/bgpd   condrestart >/dev/null 2>&1
fi
if [ -f %{_infodir}/%{name}.inf* ]; then
	/sbin/install-info --delete %{_infodir}/quagga.info %{_infodir}/dir || :
fi


%preun
if [ "$1" = "0" ]; then
    /sbin/chkconfig --del zebra
	/sbin/chkconfig --del ripd
%if %with_ipv6
	/sbin/chkconfig --del ripngd
%endif
	/sbin/chkconfig --del ospfd
%if %with_ipv6
	/sbin/chkconfig --del ospf6d
%endif
	/sbin/chkconfig --del bgpd
fi

%clean
rm -rf $RPM_BUILD_ROOT

%files
%defattr(-,root,root)
%doc */*.sample* AUTHORS COPYING
%doc doc/quagga.html
%doc doc/mpls
%doc ChangeLog INSTALL NEWS README REPORTING-BUGS SERVICES TODO
%if %quagga_user
%dir %attr(751,%quagga_user,%quagga_user) %{_sysconfdir}
%dir %attr(770,%quagga_user,%quagga_user) /var/log/quagga 
%dir %attr(771,%quagga_user,%quagga_user) /var/run/quagga
%else
%dir %attr(750,root,root) %{_sysconfdir}
%dir %attr(750,root,root) /var/log/quagga
%dir %attr(755,root,root) /usr/share/info
%dir %attr(750,root,root) /var/run/quagga
%endif
%if %vty_group
# noreplace not used due to smaple conf file
%config %attr(644,%quagga_user,%vty_group) %{_sysconfdir}/vtysh.conf.sample
%endif
%{_infodir}/*info*
%{_mandir}/man*/*
%{_sbindir}/*
%if %with_vtysh
%{_bindir}/*
%endif
%dir %{_libdir}
%{_libdir}/*.so*
# noreplace not used due to smaple conf files
%config /etc/quagga/[!v]*
%attr(755,root,root) /etc/rc.d/init.d/*
%if %with_pam
%config(noreplace) /etc/pam.d/quagga
%endif
%config(noreplace) %attr(640,root,root) /etc/logrotate.d/quagga
%config(noreplace) /etc/sysconfig/quagga                  


%files contrib
%defattr(-,root,root)
%doc %attr(0644,root,root) tools

%files devel
%defattr(-,root,root)
%dir %{_libdir}
%{_libdir}/*.a
%{_libdir}/*.la
%dir %{_includedir}/quagga
%{_includedir}/quagga/*.h
%dir %{_includedir}/quagga/ospfd
%{_includedir}/quagga/ospfd/*.h
%if %with_ospfapi
%dir %{_includedir}/quagga/ospfapi
%{_includedir}/quagga/ospfapi/*.h
%endif

%changelog
* Mon Feb 11 2013 Guillermo Ontanon <guillermo midokura jp> - 0.99.21-1

New upstream version.

* Fri Aug 17 2012 Adam Tkac <atkac redhat com> - 0.99.15-7.2
- improve fix for CVE-2011-3325

* Wed Aug 08 2012 Adam Tkac <atkac redhat com> - 0.99.15-7.1
- fix CVE-2011-3323
- fix CVE-2011-3324
- fix CVE-2011-3325
- fix CVE-2011-3326
- fix CVE-2011-3327
- fix CVE-2012-0255
- fix CVE-2012-0249 and CVE-2012-0250
- fix CVE-2012-1820

* Thu Mar 03 2011 Jiri Skala <jskala@redhat.com> - 0.99.15-7
- Resolves: #684751 - CVE-2010-1674 CVE-2010-1675 quagga various flaws

* Wed Oct 20 2010 Jiri Skala <jskala@redhat.com> - 0.99.15-6
- Resolves: #644832 - CVE-2010-2948 CVE-2010-2949 quagga various flaws

* Thu May 27 2010 Jiri Skala <jskala@redhat.com> - 0.99.15-5
- Resolves: #596202 - added -fno-strict-aliasing flag

* Wed Feb 10 2010 Jiri Skala <jskala@redhat.com> - 0.99.15-4
- Resolves: #563260 - built without PAM but shipping PAM config file and requires pam

* Fri Jan 29 2010 Jiri Skala <jskala@redhat.com> - 0.99.15-3
- Resolves: #555835 spec corrections noticed by rpmlint

* Tue Jan 12 2010 Jiri Skala <jskala@redhat.com> - 0.99.15-2
- Related: rhbz#543948
- little fixes in spec due to rpmlint

* Fri Dec 11 2009 Jiri Skala <jskala@redhat.com> - 0.99.15-1
- latest upstream version
- back ported posix compliance of init script

* Mon Sep 14 2009 Jiri Skala <jskala@redhat.com> - 0.99.12-3
- fixed #516005 - Errors installing quagga-0.99.11-2.fc11.i586 with --excludedocs
- fixed #522787 - quagga: build future versions without PAM

* Sun Jul 26 2009 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 0:0.99.12-3
- Rebuilt for https://fedoraproject.org/wiki/Fedora_12_Mass_Rebuild

* Tue Jul 14 2009 Jiri Skala <jskala@redhat.com> - 0.99.12-2
- replaced Obsoletes by Conflicts

* Wed May 20 2009 Jiri Skala <jskala@redhat.com> - 0.99.12-1
- update to latest upstream version
- fix #499960 - BGPd in Quagga prior to 0.99.12 has a serious assert problem crashing with ASN4's

* Mon May 04 2009 Jiri Skala <jskala@redhat.com> - 0.99.11-3
- fix #498832 - bgpd crashes on as paths containing more 6 digit as numbers
- corrected release number

* Wed Feb 25 2009 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 0:0.99.11-2
- Rebuilt for https://fedoraproject.org/wiki/Fedora_11_Mass_Rebuild

* Tue Jan 06 2009 Jiri Skala <jskala@redhat.com> - 0.99.11-1
- bump to latest upstream version 0.99.11

* Wed Sep  3 2008 Tom "spot" Callaway <tcallawa@redhat.com> - 0.99.10-2
- fix license tag

* Wed Jun 11 2008 Martin Nagy <mnagy@redhat.com> - 0.99.10-1
- upgrade to new upstream 0.99.10

* Wed Mar 05 2008 Martin Nagy <mnagy@redhat.com> - 0.99.9-6
- fix vtysh.conf owner and group (#416121)
- fix bpgd IPv6 advertisements, patch from upstream (#429448)

* Mon Feb 11 2008 Martin Nagy <mnagy@redhat.com> - 0.99.9-5
- rebuild for gcc-4.3

* Tue Jan 29 2008 Martin Nagy <mnagy@redhat.com> - 0.99.9-4
- check port number range when using -P or -p (#206071)

* Wed Jan 23 2008 Martin Nagy <mnagy@redhat.com> - 0.99.9-3
- rebuild

* Mon Nov 12 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.9-2
- resolves: #376821: ospfd init script looks for ospf6 configuration and lock file
- resolves: #376841: Usage string in ospf6d init script incorrect

* Mon Sep 17 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.9-1
- upgrade to new upstream 0.99.9
- Resolves: #292841: CVE-2007-4826 quagga bgpd DoS

* Fri Sep 14 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.8-1.1
- rebuild

* Mon Jul 30 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.8-1
- upgrade to new upstream version 0.99.8
- resolves: #249423: scripts in /etc/rc.d/init.d/* are marked as config files in specfile
- resolves: #247040: Initscript Review
- resolves: #249538: Inconsistencies in init scripts
- resolves: #220531: quagga: non-failsafe install-info usage, info files removed from index on update

* Tue Jul  3 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.7-1
- upgrade to new upstream 0.99.7
- resolves: #240488: CVE-2007-1995 Quagga bgpd DoS

* Wed Mar 28 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.99.6-1
- upgrade to new upstream 0.99.6
- Resolves: #233909: quagga: unowned directory
- removed redundant patches (#226352)

* Mon Jan 22 2007 Martin Bacovsky <mbacovsk@redhat.com> - 0.98.6-3
- Resolves: #172548 - quagga.spec defines with_vtysh 1 but vtysh is not enabled in the build

* Wed Jul 12 2006 Jesse Keating <jkeating@redhat.com> - 0:0.98.6-2.1
- rebuild

* Mon May 8 2006 Jay Fenlason <fenlason@redhat.com> 0:0.98.6-2
- Upgrade to new upstream version, closing security problems:
  bz#191081 CVE-2006-2223 Quagga RIPd information disclosure
  bz#191085 CVE-2006-2224 Quagga RIPd route injection

* Wed Mar  8 2006 Bill Nottingham <notting@redhat.com> - 0:0.98.5-4
- use an assigned gid for quaggavt

* Fri Feb 10 2006 Jesse Keating <jkeating@redhat.com> - 0:0.98.5-3.2.1
- bump again for double-long bug on ppc(64)

* Tue Feb 07 2006 Jesse Keating <jkeating@redhat.com> - 0:0.98.5-3.2
- rebuilt for new gcc4.1 snapshot and glibc changes

* Fri Dec 09 2005 Jesse Keating <jkeating@redhat.com>
- rebuilt

* Wed Oct 19 2005 Jay Fenlason <fenlason@redhat.com> 0.98.5-3
- add the -pie patch, to make -fPIE compiling actually work on all platforms.
- Include -pam patch to close
  bz#170256 ? pam_stack is deprecated
- Change ucd-snmp to net-snmp to close
  bz#164333 ? quagga 0.98.4-2 requires now obsolete package ucd-snmp-devel
- also fix duplicate line mentioned in bz#164333

* Mon Aug 29 2005 Jay Fenlason <fenlason@redhat.com> 0.98.5-2
- New upstream version.

* Mon Jun 27 2005 Jay Fenlason <fenlason@redhat.com> 0.98.4-2
- New upstream version.

* Mon Apr 4 2005 Jay Fenlason <fenlason@redhat.com> 0.98.3-2
- new upstream verison.
- remove the -bug157 patch.

* Tue Mar 8 2005 Jay Fenlason <fenlason@redhat.com> 0.98.2-2
- New upstream version, inclues my -gcc4 patch, and a patch from
  Vladimir Ivanov <wawa@yandex-team.ru>

* Sun Jan 16 2005 Jay Fenlason <fenlason@redhat.com> 0.98.0-2
- New upstream version.  This fixes bz#143796
- --with-rtadv needs to be --enable-rtadv according to configure.
  (but it was enabled by default, so this is a cosmetic change only)
  Change /etc/logrotate.d/* to /etc/logrotate.d/quagga in this spec file.
- Modify this spec file to move the .so files to the base package
  and close bz#140894
- Modify this spec file to separate out _includedir/quagga/ospfd
  from .../*.h and _includedir/quagga/ospfapi from .../*.h
  Rpmbuild probably shouldn't allow dir tag of a plain file.

* Wed Jan 12 2005 Tim Waugh <twaugh@redhat.com> 0.97.3-3
- Rebuilt for new readline.
- Don't explicitly require readline.  The correct dependency is automatically
  picked up by linking against it.

* Fri Nov 26 2004 Florian La Roche <laroche@redhat.com>
- remove target buildroot again to not waste disk space

* Wed Nov 10 2004 Jay Fenlason <fenlason@redhat.com> 0.97.3-1
- New upstream version.

* Tue Oct 12 2004 Jay Fenlason <fenlason@redhat.com> 0.97.2-1
- New upstream version.

* Fri Oct 8 2004 Jay Fenlason <fenlason@redhat.com> 0.97.0-1
- New upstream version.  This obsoletes the -lib64 patch.

* Wed Oct 6 2004 Jay Fenlason <fenlason@redhat.com>
- Change the permissions of /var/run/quagga to 771 and /var/log/quagga to 770
  This closes #134793

* Thu Sep  9 2004 Bill Nottingham <notting@redhat.com> 0.96.5-2
- don't run by default

* Tue Jun 15 2004 Elliot Lee <sopwith@redhat.com>
- rebuilt

* Tue May 4 2004 Jay Fenlason <fenlason@redhat.com> 0.96.5-0
- New upstream version
- Change includedir
- Change the pre scriptlet to fail if the useradd command fails.
- Remove obsolete patches from this .spec file and renumber the two
  remaining ones.

* Tue Mar 02 2004 Elliot Lee <sopwith@redhat.com>
- rebuilt

* Fri Feb 13 2004 Jay Fenlason <fenlason@redhat.com>
- Add --enable-rtadv, to turn ipv6 router advertisement back on.  Closes
  bugzilla #114691

* Fri Feb 13 2004 Elliot Lee <sopwith@redhat.com>
- rebuilt

* Mon Nov 3 2003 Jay Fenlason <fenlason@redhat.com> 0.96.4-0
- New upstream version
- include .h files in the -devel package.

* Wed Oct 15 2003 Jay Fenlason <fenlason@redhat.com> 0.96.3-1
- Patch a remote DoS attack (#107140)
- New upstream version
- Removed the .libcap patch, which was applied upstream
- Renamed the vty group from quaggavty to quaggavt because quaggavty is
  too long for NIS.
- Patch the spec file to fix #106857: /etc/quagga/zebra.conf is created with
  the wrong owner.
- Removed the "med" part of the 0.96.1-warnings patch for . . .
- Add a new warnings patch with a fix for #106315.  This also updates
  the "med" part of the previous warnings patch.

* Mon Sep 22 2003 Jay Fenlason <fenlason@redhat.com> 0.96.2-5
- merge sysconfig patch from 3E branch.  This patch is from Kaj J. Niemi
  (kajtzu@basen.net), and puts Quagga configuration options in
  /etc/sysconfig/quagga, and defaults Quagga to listening on 127.0.0.1 only.
  This closes #104376
- Use /sbin/nologin for the shell of the quagga user.  This closes #103320
- Update the quagga-0.96.1-warnings.patch patch to kill a couple more
  warnings.

* Mon Sep 22 2003 Nalin Dahyabhai <nalin@redhat.com>
- Remove the directory part of paths for PAM modules in quagga.pam, allowing
  libpam to search its default directory (needed if a 64-bit libpam needs to
  look in /lib64/security instead of /lib/security).

* Tue Aug 26 2003 Jay Fenlason <fenlason@redhat.com> 0.96.2-1
- New upstream version

* Tue Aug 19 2003 Jay Fenlason <fenlason@redhat.com> 0.96.1-3
- Merge from quagga-3E-branch, with a fix for #102673 and a couple
  more compiler warnings quashed.

* Wed Aug 13 2003 Jay Fenlason <fenlason@redhat.com> 0.96-1
- added a patch (libwrap) to allow the generated Makefiles for zebra/
  and lib/ to work on AMD64 systems.  For some reason make there does
  not like a dependency on -lcap
- added a patch (warnings) to shut up warnings about an undefined
  structure type in some function prototypes and quiet down all the
  warnings about assert(ptr) on x86_64.
- Modified the upstream quagga-0.96/readhat/quagga.spec to work as an
  official Red Hat package (remove user and group creation and changes
  to services)
- Trimmed changelog.  See the upstream .spec file for previous
  changelog entries.
