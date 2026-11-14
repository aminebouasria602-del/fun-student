package com.example.student;

import com.example.student.configuration.AppInitializer;
import com.example.student.configuration.SecurityConfiguration;
import com.example.student.controller.AuthController;
import com.example.student.controller.UserController;
import com.example.student.dto.AuthResponse;
import com.example.student.dto.LoginRequest;
import com.example.student.dto.user.UserRequest;
import com.example.student.dto.user.UserResponse;
import com.example.student.dto.user.UserResponseList;
import com.example.student.exception.EmailAlreadyExistsException;
import com.example.student.exception.EntityNotFoundException;
import com.example.student.mapper.UserMapperImpl;
import com.example.student.model.Role;
import com.example.student.model.Student;
import com.example.student.model.User;
import com.example.student.repository.StudentRepository;
import com.example.student.repository.UserRepository;
import com.example.student.security.JwtFilter;
import com.example.student.security.JwtService;
import com.example.student.service.CustomUserDetailsService;
import com.example.student.service.UserService;
import com.example.student.service.impl.UserServiceImpl;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAndSecurityTest {

    private static UserDetails springUser(String email, String... authorities) {
        return org.springframework.security.core.userdetails.User.withUsername(email)
                .password("pw")
                .authorities(authorities)
                .build();
    }

    private static User user(long id, String email, Role... roles) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setPassword("pw");
        u.setRoles(Set.of(roles));
        return u;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class UserMapperTests {
        private final UserMapperImpl mapper = new UserMapperImpl();

        @Test
        void toEntityCopiesFields() {
            User u = mapper.toEntity(new UserRequest("a@b.c", "secret", Set.of(Role.ADMIN)));
            assertThat(u.getEmail()).isEqualTo("a@b.c");
            assertThat(u.getPassword()).isEqualTo("secret");
            assertThat(u.getRoles()).containsExactly(Role.ADMIN);
        }

        @Test
        void toResponseCopiesFields() {
            UserResponse r = mapper.toResponse(user(3L, "a@b.c", Role.TEACHER));
            assertThat(r.getId()).isEqualTo(3L);
            assertThat(r.getEmail()).isEqualTo("a@b.c");
            assertThat(r.getRoles()).containsExactly(Role.TEACHER);
        }

        @Test
        void toListResponseCountsUsers() {
            UserResponseList list = mapper.toListResponse(List.of(user(1L, "a@b.c", Role.ADMIN), user(2L, "d@e.f", Role.TEACHER)));
            assertThat(list.getTotalCount()).isEqualTo(2);
            assertThat(list.getResponses()).hasSize(2);
            assertThat(mapper.toResponseList(List.of())).isEmpty();
        }
    }

    @Nested
    class UserServiceTests {
        private final UserRepository repository = mock(UserRepository.class);
        private final PasswordEncoder encoder = mock(PasswordEncoder.class);
        private final UserServiceImpl service = new UserServiceImpl(repository, new UserMapperImpl(), encoder);

        @Test
        void existsByEmailDelegatesToRepository() {
            when(repository.existsByEmail("a@b.c")).thenReturn(true);
            assertThat(service.existsByEmail("a@b.c")).isTrue();
            assertThat(service.existsByEmail("x@y.z")).isFalse();
        }

        @Test
        void registerEncodesPasswordAndSaves() {
            when(encoder.encode("secret")).thenReturn("enc");
            boolean result = service.register(new UserRequest("a@b.c", "secret", Set.of(Role.ADMIN)));
            assertThat(result).isTrue();
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEqualTo("enc");
        }

        @Test
        void registerWithBlankPasswordSkipsEncoding() {
            boolean result = service.register(new UserRequest("a@b.c", "", Set.of(Role.ADMIN)));
            assertThat(result).isTrue();
            verify(encoder, never()).encode(any());
            verify(repository).save(any(User.class));
        }

        @Test
        void registerReturnsFalseWhenEmailMissing() {
            assertThat(service.register(new UserRequest(null, "secret", Set.of()))).isFalse();
            verify(repository, never()).save(any(User.class));
        }

        @Test
        void registerReturnsFalseWhenEmailExists() {
            when(repository.existsByEmail("a@b.c")).thenReturn(true);
            assertThat(service.register(new UserRequest("a@b.c", "secret", Set.of()))).isFalse();
            verify(repository, never()).save(any(User.class));
        }

        @Test
        void findByEmailReturnsUser() {
            when(repository.findByEmail("a@b.c")).thenReturn(Optional.of(user(1L, "a@b.c", Role.ADMIN)));
            assertThat(service.findByEmail("a@b.c").getId()).isEqualTo(1L);
        }

        @Test
        void findByEmailThrowsWhenMissing() {
            when(repository.findByEmail("x@y.z")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.findByEmail("x@y.z"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("x@y.z");
        }

        @Test
        void findAllReturnsEveryUser() {
            when(repository.findAll()).thenReturn(List.of(user(1L, "a@b.c", Role.ADMIN), user(2L, "d@e.f", Role.TEACHER)));
            assertThat(service.findAll().getTotalCount()).isEqualTo(2);
        }

        @Test
        void saveCreatesUserWithEncodedPassword() {
            when(encoder.encode("secret")).thenReturn("enc");
            when(repository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(10L);
                return u;
            });
            UserResponse r = service.save(new UserRequest("a@b.c", "secret", Set.of(Role.ADMIN)));
            assertThat(r.getId()).isEqualTo(10L);
            assertThat(r.getEmail()).isEqualTo("a@b.c");
            assertThat(r.getRoles()).containsExactly(Role.ADMIN);
        }

        @Test
        void saveThrowsWhenEmailAlreadyExists() {
            when(repository.existsByEmail("a@b.c")).thenReturn(true);
            assertThatThrownBy(() -> service.save(new UserRequest("a@b.c", "secret", Set.of())))
                    .isInstanceOf(EmailAlreadyExistsException.class)
                    .hasMessageContaining("a@b.c");
        }

        @Test
        void updateUserChangesRolesOfExistingUser() {
            User existing = user(1L, "a@b.c", Role.TEACHER);
            when(repository.findByEmail("a@b.c")).thenReturn(Optional.of(existing));
            service.updateUser(new UserRequest("a@b.c", null, Set.of(Role.ADMIN)));
            assertThat(existing.getRoles()).containsExactly(Role.ADMIN);
        }

        @Test
        void updateUserThrowsWhenMissing() {
            when(repository.findByEmail("x@y.z")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateUser(new UserRequest("x@y.z", null, Set.of())))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void deleteByIdCallsRepository() {
            service.deleteById(4L);
            verify(repository).deleteById(4L);
        }
    }

    @Nested
    class UserControllerTests {
        private final UserService service = mock(UserService.class);
        private final UserController controller = new UserController(service);

        @Test
        void getAllUsersDelegatesToService() {
            UserResponseList expected = new UserResponseList(List.of(), 0);
            when(service.findAll()).thenReturn(expected);
            assertThat(controller.getAllUsers()).isSameAs(expected);
        }

        @Test
        void updateUserDelegatesToService() {
            UserRequest request = new UserRequest("a@b.c", null, Set.of(Role.ADMIN));
            UserResponse expected = new UserResponse(1L, "a@b.c", Set.of(Role.ADMIN));
            when(service.updateUser(request)).thenReturn(expected);
            assertThat(controller.updateUser(request)).isSameAs(expected);
        }

        @Test
        void saveDelegatesToService() {
            UserRequest request = new UserRequest("a@b.c", "secret", Set.of(Role.TEACHER));
            UserResponse expected = new UserResponse(2L, "a@b.c", Set.of(Role.TEACHER));
            when(service.save(request)).thenReturn(expected);
            assertThat(controller.save(request)).isSameAs(expected);
        }

        @Test
        void deleteByIdDelegatesToService() {
            controller.deleteById(5L);
            verify(service).deleteById(5L);
        }
    }

    @Nested
    class AuthControllerTests {
        private final AuthenticationManager manager = mock(AuthenticationManager.class);
        private final JwtService jwtService = new JwtService();
        private final AuthController controller = new AuthController(manager, jwtService);

        @Test
        void loginReturnsTokenAndRoles() {
            UserDetails details = springUser("admin@example.com", "ROLE_ADMIN");
            when(manager.authenticate(any())).thenReturn(
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
            AuthResponse r = controller.login(new LoginRequest("admin@example.com", "admin"));
            assertThat(r.getToken()).isNotBlank();
            assertThat(r.getType()).isEqualTo("Bearer");
            assertThat(r.getExpiresIn()).isEqualTo(3600);
            assertThat(r.getRoles()).containsExactly("ROLE_ADMIN");
            assertThat(jwtService.extractUsername(r.getToken())).isEqualTo("admin@example.com");
            assertThat(jwtService.extractRoles(r.getToken())).containsExactly(Role.ADMIN);
        }

        @Test
        void loginWithWrongPasswordFails() {
            when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
            assertThatThrownBy(() -> controller.login(new LoginRequest("admin@example.com", "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Nested
    class JwtServiceTests {
        private final JwtService jwtService = new JwtService();

        @Test
        void tokenContainsUsernameAndRoles() {
            String token = jwtService.generateToken("a@b.c", Set.of(Role.ADMIN, Role.TEACHER));
            assertThat(jwtService.extractUsername(token)).isEqualTo("a@b.c");
            assertThat(jwtService.extractRoles(token)).containsExactlyInAnyOrder(Role.ADMIN, Role.TEACHER);
        }

        @Test
        void validateTokenChecksUsername() {
            String token = jwtService.generateToken("a@b.c", Set.of(Role.ADMIN));
            assertThat(jwtService.validateToken(token, springUser("a@b.c", "ROLE_ADMIN"))).isTrue();
            assertThat(jwtService.validateToken(token, springUser("other@b.c", "ROLE_ADMIN"))).isFalse();
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            Field field = JwtService.class.getDeclaredField("SECRET_KEY");
            field.setAccessible(true);
            SecretKey key = (SecretKey) field.get(jwtService);
            String expired = Jwts.builder()
                    .subject("a@b.c")
                    .expiration(new Date(System.currentTimeMillis() - 60_000))
                    .signWith(key)
                    .compact();
            assertThatThrownBy(() -> jwtService.extractUsername(expired))
                    .isInstanceOf(ExpiredJwtException.class);
        }
    }

    @Nested
    class JwtFilterTests {
        private final JwtService jwtService = new JwtService();
        private final UserDetailsService detailsService = mock(UserDetailsService.class);
        private final JwtFilter filter = new JwtFilter(detailsService, jwtService);

        @Test
        void requestWithoutHeaderPassesThroughUnauthenticated() throws Exception {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        void validTokenAuthenticatesTheUser() throws Exception {
            String token = jwtService.generateToken("admin@example.com", Set.of(Role.ADMIN));
            when(detailsService.loadUserByUsername("admin@example.com"))
                    .thenReturn(springUser("admin@example.com", "ROLE_ADMIN"));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("admin@example.com");
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        void tokenForOtherUserDoesNotAuthenticate() throws Exception {
            String token = jwtService.generateToken("admin@example.com", Set.of(Role.ADMIN));
            when(detailsService.loadUserByUsername("admin@example.com"))
                    .thenReturn(springUser("other@example.com", "ROLE_ADMIN"));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        void invalidTokenOnApiPathGives401() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/api/v1/student");
            request.addHeader("Authorization", "Bearer not-a-jwt");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("error");
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        void invalidTokenOnOtherPathIsRethrown() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/index.html");
            request.addHeader("Authorization", "Bearer not-a-jwt");
            assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    class CustomUserDetailsServiceTests {
        private final UserRepository repository = mock(UserRepository.class);
        private final CustomUserDetailsService service = new CustomUserDetailsService(repository);

        @Test
        void loadsUserWithRolePrefix() {
            when(repository.findByEmail("a@b.c")).thenReturn(Optional.of(user(1L, "a@b.c", Role.ADMIN)));
            UserDetails details = service.loadUserByUsername("a@b.c");
            assertThat(details.getUsername()).isEqualTo("a@b.c");
            assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        }

        @Test
        void throwsWhenUserMissing() {
            when(repository.findByEmail("x@y.z")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.loadUserByUsername("x@y.z"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }
    }

    @Nested
    class AppInitializerTests {
        private final UserRepository userRepository = mock(UserRepository.class);
        private final StudentRepository studentRepository = mock(StudentRepository.class);
        private final PasswordEncoder encoder = mock(PasswordEncoder.class);
        private final AppInitializer initializer = new AppInitializer(userRepository, encoder, studentRepository);

        @Test
        void createsUsersAndStudentsWhenDatabaseIsEmpty() {
            when(encoder.encode(any())).thenReturn("enc");
            initializer.init();
            verify(userRepository, times(3)).save(any(User.class));
            verify(studentRepository, times(5)).save(any(Student.class));
        }

        @Test
        void createsNothingWhenDataAlreadyExists() {
            when(userRepository.existsByEmail(any())).thenReturn(true);
            when(studentRepository.count()).thenReturn(5L);
            initializer.init();
            verify(userRepository, never()).save(any(User.class));
            verify(studentRepository, never()).save(any(Student.class));
        }
    }

    @Nested
    class SecurityConfigurationTests {
        private final SecurityConfiguration config = new SecurityConfiguration(mock(JwtFilter.class));

        @Test
        void passwordEncoderHashesAndMatches() {
            PasswordEncoder encoder = config.passwordEncoder();
            String hash = encoder.encode("admin");
            assertThat(hash).isNotEqualTo("admin");
            assertThat(encoder.matches("admin", hash)).isTrue();
            assertThat(encoder.matches("wrong", hash)).isFalse();
        }

        @Test
        void authenticationManagerComesFromConfiguration() throws Exception {
            AuthenticationConfiguration configuration = mock(AuthenticationConfiguration.class);
            AuthenticationManager manager = mock(AuthenticationManager.class);
            when(configuration.getAuthenticationManager()).thenReturn(manager);
            assertThat(config.authenticationManager(configuration)).isSameAs(manager);
        }
    }

    @Nested
    class ModelAndDtoTests {
        @Test
        void userBuilderStartsWithEmptyRoles() {
            User u = User.builder().id(1L).email("a@b.c").password("pw").build();
            assertThat(u.getRoles()).isEmpty();
            assertThat(u.getEmail()).isEqualTo("a@b.c");
        }

        @Test
        void dtosKeepTheirValues() {
            LoginRequest login = new LoginRequest();
            login.setEmail("a@b.c");
            login.setPassword("pw");
            assertThat(login.getEmail()).isEqualTo("a@b.c");
            assertThat(login.getPassword()).isEqualTo("pw");

            AuthResponse auth = new AuthResponse();
            auth.setToken("t");
            auth.setType("Bearer");
            auth.setExpiresIn(10);
            auth.setRoles(List.of("ROLE_ADMIN"));
            assertThat(auth.getToken()).isEqualTo("t");
            assertThat(auth.getRoles()).containsExactly("ROLE_ADMIN");

            UserResponse response = new UserResponse();
            response.setId(1L);
            response.setEmail("a@b.c");
            response.setRoles(Set.of(Role.ADMIN));
            assertThat(response.getId()).isEqualTo(1L);

            UserRequest request = new UserRequest();
            request.setEmail("a@b.c");
            request.setPassword("pw");
            request.setRoles(Set.of(Role.TEACHER));
            assertThat(request.getRoles()).containsExactly(Role.TEACHER);

            assertThat(Role.valueOf("SUPER_ADMIN")).isEqualTo(Role.SUPER_ADMIN);
        }
    }
}
